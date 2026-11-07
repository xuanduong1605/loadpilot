package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal saved-binding preparation facade. Client acquisition happens after the committed intent. */
@Service
public class WorkerPreparationService {
    private final RunIntents runs;private final ExecutionPlans plans;private final ExecutionClusterClients clients;
    private final WorkerPreparationDispatch dispatch;private final ObjectMapper json;
    public WorkerPreparationService(RunIntents runs,ExecutionPlans plans,ExecutionClusterClients clients,WorkerPreparationDispatch dispatch,ObjectMapper json) {
        this.runs=runs;this.plans=plans;this.clients=clients;this.dispatch=dispatch;this.json=json;
    }
    @Transactional(propagation=Propagation.NEVER)
    public WorkerPreparationDispatch.Result prepare(UUID runId,String expectedHash,KubernetesWorkerPreparer.Resource resource) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Worker preparation must begin outside an existing transaction.");
        return dispatch.execute(runId,expectedHash,resource,(exact,submit,requested)->{
            var run=runs.get(runId);var binding=plans.clusterBinding(runId);
            var scope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),null,ExecutionClusterClients.Access.PREPARE);
            try(var session=clients.openPreparation(scope,binding,exact,requested);var preparer=new KubernetesWorkerPreparer(session.client(),json)) {
                return preparer.create(exact,submit,requested);
            } catch(ExecutionClusterClients.Problem denied) {throw new KubernetesWorkerPreparer.Problem(denied.status(),"Saved cluster preparation scope could not be opened.");}
        });
    }
}
