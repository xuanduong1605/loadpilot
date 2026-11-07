package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal scoped activation facade, not runtime authorization or a public Start endpoint. */
@Service
public class WorkerActivationService {
    private final RunIntents runs;private final ExecutionPlans plans;private final ExecutionClusterClients clients;private final WorkerActivationDispatch dispatch;private final ObjectMapper json;
    public WorkerActivationService(RunIntents runs,ExecutionPlans plans,ExecutionClusterClients clients,WorkerActivationDispatch dispatch,ObjectMapper json) {this.runs=runs;this.plans=plans;this.clients=clients;this.dispatch=dispatch;this.json=json;}
    @Transactional(propagation=Propagation.NEVER)
    public WorkerActivationDispatch.Result activate(UUID runId,String expectedHash) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Worker activation must begin outside an existing transaction.");
        return dispatch.activate(runId,expectedHash,(exact,resources)->{
            var binding=plans.clusterBinding(runId);var run=runs.get(runId);
            var scope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),resources.jobUid(),ExecutionClusterClients.Access.ACTIVATE);
            try(var session=clients.openActivationRead(scope,binding);var activator=new KubernetesWorkerActivator(session.client(),json)) {return activator.prepare(exact,resources);}
            catch(ExecutionClusterClients.Problem denied) {throw new KubernetesWorkerActivator.Problem(denied.status(),"Activation inspection scope is unavailable.");}
        },(command,operation)->{
            var binding=plans.clusterBinding(runId);var run=runs.get(runId);
            var scope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),command.jobUid(),ExecutionClusterClients.Access.ACTIVATE);
            try(var session=clients.openActivationPatch(scope,binding,command);var activator=new KubernetesWorkerActivator(session.client(),json)) {return activator.activate(command,operation);}
            catch(ExecutionClusterClients.Problem denied) {throw new KubernetesWorkerActivator.Problem(denied.status(),"Activation patch scope is unavailable.");}
        });
    }
}
