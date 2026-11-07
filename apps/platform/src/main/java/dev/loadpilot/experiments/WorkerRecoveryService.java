package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource;

/** Recovery only: no create retry, patch, activation, plan alteration or runtime authority. */
@Service
public class WorkerRecoveryService {
    private final RunIntents runs;private final ExecutionPlans plans;private final ExecutionClusterClients clients;private final WorkerRecoveryStore store;private final RunObservations observations;private final ObjectMapper json;
    public WorkerRecoveryService(RunIntents runs,ExecutionPlans plans,ExecutionClusterClients clients,WorkerRecoveryStore store,RunObservations observations,ObjectMapper json){this.runs=runs;this.plans=plans;this.clients=clients;this.store=store;this.observations=observations;this.json=json;}
    @Transactional(propagation=Propagation.NEVER) public void reconcileStopped(UUID id){
        var run=runs.get(id);if(run.status()!=RunLifecycle.Status.STOPPING || run.workerUid()!=null)return;
        var rows=store.rows(id);var job=rows.stream().filter(r->r.resource()==Resource.JOB).findFirst().orElse(null);
        if(job==null){cleanup(id,false);return;}
        var saved=plans.get(id);var exact=new K6PlanEnvelope.Prepared(saved.canonicalJson(),saved.sha256());var binding=plans.clusterBinding(id);
        var found=inspect(run,binding,exact,job,job.knownUid()!=null);
        String uid=job.knownUid();if(found!=null){store.bind(id,job.preparationId(),found);uid=found.uid();}
        if(uid==null)return; //404 after an uncertain CREATE cannot release the target guard.
        var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),run.revision(),run.attemptId(),run.shardId(),saved.submitOperationId(),run.workerName(),run.maximumSeconds(),run.deadlineAt());
        var scope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),uid,ExecutionClusterClients.Access.READ);
        RunObservations.Observation evidence;
        try(var session=clients.openBound(scope,binding);var collector=new KubernetesWorkerCollector(session.client())){
            evidence=found==null?collector.confirmAbsent(ticket,plans.workerJob(run),uid):collector.collect(ticket,plans.workerJob(run),uid);
        }
        observations.record(id,evidence); // Revision/UID recheck after I/O; never treat inspection as SLO evidence.
    }
    @Transactional(propagation=Propagation.NEVER) public boolean cleanup(UUID id,boolean finalResultSaved){
        var run=runs.get(id);boolean before=store.beforeJob(id);
        if(!run.status().terminal() && !before)return false;
        var rows=store.rows(id);if(rows.isEmpty()){if(before)store.completeBeforeJob(id);return true;}
        var saved=plans.get(id);var exact=new K6PlanEnvelope.Prepared(saved.canonicalJson(),saved.sha256());var binding=plans.clusterBinding(id);
        // Remove Job before dependent files/network. Completion retains the Job until its summary is durable.
        for(var resource:List.of(Resource.JOB,Resource.NETWORK,Resource.FILES))for(var row:rows)if(row.resource()==resource && row.absentAt()==null){
            var found=inspect(run,binding,exact,row,run.workerUid()!=null);
            if(found==null){if(row.knownUid()==null)return false;store.absent(id,row.preparationId(),row.knownUid());continue;}
            if(resource==Resource.JOB && !finalResultSaved)return false; // Do not discard a possible partial/final summary.
            var claim=store.claimDelete(id,row.preparationId(),found);
            if(claim.dispatch()){
                var command=new KubernetesWorkerRecovery.Delete(resource,binding.namespace(),run.workerName(),claim.row().knownUid(),claim.row().deleteVersion());
                var scope=scope(run,binding);
                try(var session=clients.openRecovery(scope,binding,exact,resource,command);var adapter=new KubernetesWorkerRecovery(session.client(),json)){store.finishDelete(row.preparationId(),adapter.delete(command));}
            }
            var known=new WorkerRecoveryStore.Row(row.preparationId(),resource,row.creationOutcome(),found.uid(),claim.row().deleteOperation(),claim.row().deleteVersion(),claim.row().deleteOutcome(),null);
            if(inspect(run,binding,exact,known,run.workerUid()!=null)!=null)return false;
            store.absent(id,row.preparationId(),found.uid());
        }
        if(store.rows(id).stream().anyMatch(r->r.absentAt()==null))return false;
        if(before)store.completeBeforeJob(id);store.completeWorkflow(id);return true;
    }
    private ExecutionClusterClients.Scope scope(RunIntents.Run run,ExecutionClusterClients.Binding binding){return new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),run.workerUid(),ExecutionClusterClients.Access.RECOVER);}
    private KubernetesWorkerRecovery.Found inspect(RunIntents.Run run,ExecutionClusterClients.Binding binding,K6PlanEnvelope.Prepared plan,WorkerRecoveryStore.Row row,boolean active){
        try(var session=clients.openRecovery(scope(run,binding),binding,plan,row.resource(),null);var adapter=new KubernetesWorkerRecovery(session.client(),json)){return adapter.inspect(plan,row.resource(),row.knownUid(),active);}
    }
}
