package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Resumes committed work; one-shot resource/activation receipts prevent duplicate external effects. */
@Service
public class RunStartProcessor {
    private final StartWorkflows queue;private final RunIntents runs;private final RunDispatch dispatch;private final ExecutionPreflight preflight;private final ExecutionPlans plans;
    private final RuntimeAdmission admission;private final WorkerPreparationService preparation;private final WorkerActivationService activation;private final RunMonitorStore monitor;
    public RunStartProcessor(StartWorkflows queue,RunIntents runs,RunDispatch dispatch,ExecutionPreflight preflight,ExecutionPlans plans,RuntimeAdmission admission,WorkerPreparationService preparation,WorkerActivationService activation,RunMonitorStore monitor){this.queue=queue;this.runs=runs;this.dispatch=dispatch;this.preflight=preflight;this.plans=plans;this.admission=admission;this.preparation=preparation;this.activation=activation;this.monitor=monitor;}
    @Transactional(propagation=Propagation.NEVER) public boolean tick(){var claim=queue.claimNext();return claim!=null && process(claim);}
    @Transactional(propagation=Propagation.NEVER) public boolean process(UUID id){var claim=queue.claim(id);return claim!=null && process(claim);}
    private boolean process(StartWorkflows.Claim claim){
        UUID id=claim.runId();
        try{
            check(claim);
            var run=runs.get(id);if(run.status()==RunLifecycle.Status.QUEUED)dispatch.prepare(id);
            check(claim);var view=queue.read(id);ExecutionPlans.Saved saved;
            if(view.planId()==null){
                var candidate=preflight.prepare(id);var grant=admission.verify(candidate.plan());
                queue.requireOwner(claim);saved=preflight.persist(id,candidate.plan().sha256(),candidate.destination(),queue.actor(id));queue.authorize(claim,saved,grant);
            }else saved=plans.get(id);
            var exact=new K6PlanEnvelope.Prepared(saved.canonicalJson(),saved.sha256());var grant=queue.read(id).admission();
            for(var resource:KubernetesWorkerPreparer.Resource.values()){
                check(claim);verify(exact,grant);
                if(!preparation.prepare(id,saved.sha256(),resource).receipt().outcome().equals("ACKNOWLEDGED"))throw new IllegalStateException("Preparation requires reconciliation.");
            }
            check(claim);verify(exact,grant);
            if(!activation.activate(id,saved.sha256()).receipt().outcome().equals("ACKNOWLEDGED"))throw new IllegalStateException("Activation requires reconciliation.");
            queue.finish(claim,"OBSERVING","OBSERVING");monitor.wake(id);
        }catch(RuntimeException stopped){
            // Only fixed status is retained; upstream source, credentials and response bodies are never status text.
            try{queue.requireOwner(claim);}catch(RuntimeException lost){return true;}
            boolean noEffects=queue.rejectWithoutEffects(id);
            if(!noEffects)runs.abortExecution(id);
            boolean cancelled=runs.get(id).status()==RunLifecycle.Status.CANCELLED;
            queue.finish(claim,cancelled?"HALTED":noEffects?"FAILED":"RECOVERY_REQUIRED",cancelled?"STOP_REQUESTED":noEffects?"START_REJECTED":"RECONCILIATION_REQUIRED");monitor.wake(id);
        }
        return true;
    }
    private void check(StartWorkflows.Claim claim){
        queue.requireOwner(claim);queue.requireActor(claim.runId());var run=runs.enforceDeadline(claim.runId());run=runs.enforceStartup(run.id());
        if(run.status()!=RunLifecycle.Status.QUEUED && run.status()!=RunLifecycle.Status.SUBMITTING)throw new IllegalStateException("Start scheduling stopped.");
    }
    private void verify(K6PlanEnvelope.Prepared plan,RuntimeAdmission.Grant grant){
        if(grant==null || !grant.planSha256().equals(plan.sha256()) || !grant.validUntil().isAfter(Instant.now()))throw new IllegalStateException("Runtime admission expired.");
        admission.recheck(plan,grant);
    }
}
