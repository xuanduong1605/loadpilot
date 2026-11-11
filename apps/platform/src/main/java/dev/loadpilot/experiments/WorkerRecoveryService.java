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
        var found=inspect(run,binding,exact,job.resource(),job.knownUid(),job.knownUid()!=null);
        String uid=job.knownUid();if(found!=null){store.bind(id,job.preparationId(),found);uid=found.uid();}
        if(uid==null)return; //404 after an uncertain CREATE cannot release the target guard.
        var ticket=ticket(run,saved);
        var scope=readScope(run,binding,uid);
        RunObservations.Observation evidence;
        try(var session=clients.openBound(scope,binding);var collector=new KubernetesWorkerCollector(session.client())){
            evidence=found==null?collector.confirmAbsent(ticket,plans.workerJob(run),uid):collector.collect(ticket,plans.workerJob(run),uid);
        }
        observations.record(id,evidence); // Revision/UID recheck after I/O; never treat inspection as SLO evidence.
    }
    @Transactional(propagation=Propagation.NEVER) public boolean cleanup(UUID id,boolean finalReadCompleted){
        var run=runs.get(id);boolean before=store.beforeJob(id);
        if(!run.status().terminal() && !before)return false;
        var rows=store.rows(id);if(rows.isEmpty()){if(before)store.completeBeforeJob(id);return true;}
        var saved=plans.get(id);var exact=new K6PlanEnvelope.Prepared(saved.canonicalJson(),saved.sha256());var binding=plans.clusterBinding(id);
        // Keep the network policy and files until both the Job and its Pods are gone.
        for(var resource:List.of(Resource.JOB,Resource.NETWORK,Resource.FILES))for(var row:rows)if(row.resource()==resource && row.absentAt()==null){
            String uid=row.knownUid();
            var found=inspect(run,binding,exact,resource,uid,run.workerUid()!=null);
            if(found!=null){
                if(resource==Resource.JOB && !finalReadCompleted)return false; // Do not discard a possible partial/final summary.
                var claim=store.claimDelete(id,row.preparationId(),found);
                if(claim!=null){
                    var command=new KubernetesWorkerRecovery.Delete(resource,binding.namespace(),run.workerName(),claim.uid(),claim.resourceVersion());
                    var scope=scope(run,binding);
                    try(var session=clients.openRecovery(scope,binding,exact,resource,command);var adapter=new KubernetesWorkerRecovery(session.client(),json)){store.finishDelete(claim.operationId(),adapter.delete(command));}
                }
                uid=found.uid();
                if(inspect(run,binding,exact,resource,uid,run.workerUid()!=null)!=null)return false;
            }
            if(uid==null)return false;
            if(resource==Resource.JOB && !workerRemoved(run,saved,binding,uid))return false;
            store.absent(id,row.preparationId(),uid);
        }
        if(store.rows(id).stream().anyMatch(r->r.absentAt()==null))return false;
        if(before)store.completeBeforeJob(id);store.completeWorkflow(id);return true;
    }
    private boolean workerRemoved(RunIntents.Run run,ExecutionPlans.Saved saved,ExecutionClusterClients.Binding binding,String uid){
        try(var session=clients.openBound(readScope(run,binding,uid),binding);var collector=new KubernetesWorkerCollector(session.client())){
            return collector.confirmRemoved(ticket(run,saved),plans.workerJob(run),uid);
        }
    }
    private static RunDispatch.Ticket ticket(RunIntents.Run run,ExecutionPlans.Saved saved){
        return new RunDispatch.Ticket(run.id(),run.snapshotId(),run.revision(),run.attemptId(),run.shardId(),saved.submitOperationId(),run.workerName(),run.maximumSeconds(),run.deadlineAt());
    }
    private static ExecutionClusterClients.Scope readScope(RunIntents.Run run,ExecutionClusterClients.Binding binding,String uid){
        return new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),uid,ExecutionClusterClients.Access.READ);
    }
    private ExecutionClusterClients.Scope scope(RunIntents.Run run,ExecutionClusterClients.Binding binding){return new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),run.workerUid(),ExecutionClusterClients.Access.RECOVER);}
    private KubernetesWorkerRecovery.Found inspect(RunIntents.Run run,ExecutionClusterClients.Binding binding,K6PlanEnvelope.Prepared plan,Resource resource,String uid,boolean active){
        try(var session=clients.openRecovery(scope(run,binding),binding,plan,resource,null);var adapter=new KubernetesWorkerRecovery(session.client(),json)){return adapter.inspect(plan,resource,uid,active);}
    }
}
