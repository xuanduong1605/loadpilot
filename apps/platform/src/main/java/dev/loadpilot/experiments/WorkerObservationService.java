package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.KubernetesWorkerCollector;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** One bounded observation, not a scheduler. Caller owns an authorized, preflighted collector. */
@Service
public class WorkerObservationService {
    private final RunIntents runs;
    private final ExecutionPlans plans;
    private final RunObservations observations;
    public WorkerObservationService(RunIntents runs,ExecutionPlans plans,RunObservations observations) {
        this.runs=runs;this.plans=plans;this.observations=observations;
    }
    @Transactional(propagation=Propagation.NEVER)
    public RunIntents.Run observe(UUID runId,KubernetesWorkerCollector collector) {
        var run=runs.get(runId);
        if(run.status()==RunLifecycle.Status.QUEUED || run.status().terminal()) throw new ExperimentDrafts.Problem(409,"This run cannot collect worker evidence.");
        var submit=run.operations().stream().filter(op->op.kind().equals("SUBMIT")).findFirst().orElseThrow();
        var expected=plans.workerJob(run);
        var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),run.revision(),run.attemptId(),run.shardId(),submit.id(),run.workerName(),run.maximumSeconds(),run.deadlineAt());
        RunObservations.Observation evidence;
        try { evidence=collector.collect(ticket,expected,run.workerUid()); }
        catch(KubernetesWorkerCollector.Problem unavailable) {
            // Permission, absence, changing identity or transport failure never proves termination or SLO failure.
            evidence=new RunObservations.Observation(UUID.randomUUID(),run.attemptId(),submit.id(),run.revision(),run.workerName(),run.workerUid(),RunLifecycle.WorkerState.UNREACHABLE,false);
            boolean pendingStop=run.operations().stream().anyMatch(op->op.kind().equals("STOP") && op.status().equals("PENDING"));
            if(unavailable.status()==404 && run.status()==RunLifecycle.Status.STOPPING && run.workerUid()!=null && submit.status().equals("CONFIRMED") && pendingStop) {
                try { evidence=collector.confirmAbsent(ticket,expected,run.workerUid()); }
                catch(KubernetesWorkerCollector.Problem stillUnknown) { /* retain the original unreachable evidence */ }
            }
        }
        if(evidence==null || evidence.expectedRevision()!=run.revision()) throw new ExperimentDrafts.Problem(409,"Collector evidence does not match the observation revision.");
        return observations.record(runId,evidence); // Fresh transaction compares revision after the external call.
    }
}
