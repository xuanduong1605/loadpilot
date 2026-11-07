package dev.loadpilot.experiments;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static dev.loadpilot.experiments.RunMonitorStore.Outcome.*;

/** Reconciles existing runs only. Never prepares resources, submits or activates a worker. */
@Service
public class RunMonitor {
    private final RunMonitorStore queue;
    private final RunIntents runs;
    private final WorkerControlService workers;
    private final WorkerResultService results;
    private final WorkerRecoveryService recovery;
    public RunMonitor(RunMonitorStore queue,RunIntents runs,WorkerControlService workers,WorkerResultService results) {
        this(queue,runs,workers,results,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RunMonitor(RunMonitorStore queue,RunIntents runs,WorkerControlService workers,WorkerResultService results,WorkerRecoveryService recovery) {
        this.queue=queue;this.runs=runs;this.workers=workers;this.results=results;this.recovery=recovery;
    }
    @Transactional(propagation=Propagation.NEVER) public boolean tick() {
        var claim=queue.claimNext();return claim!=null && process(claim);
    }
    @Transactional(propagation=Propagation.NEVER) public boolean process(UUID runId) {
        var claim=queue.claim(runId);return claim!=null && process(claim);
    }
    private boolean process(RunMonitorStore.Claim claim) {
        var outcome=UNAVAILABLE;
        try {
            var run=runs.enforceDeadline(claim.runId()); // Commit a timeout/stop intent before any external I/O.
            if(recovery!=null && run.status()==RunLifecycle.Status.STOPPING && run.workerUid()==null){recovery.reconcileStopped(run.id());run=runs.get(run.id());}
            if(!run.status().terminal() && run.status()!=RunLifecycle.Status.QUEUED) {
                // Before activation a new workflow intentionally has no observable Job yet.
                boolean preparing=run.status()==RunLifecycle.Status.SUBMITTING && run.workerUid()==null && queue.startPreparing(run.id());
                try {if(!preparing)run=workers.observe(run.id());}
                finally {runs.enforceStartup(run.id());} // Persist timeout even when client acquisition/observation throws.
                run=runs.get(run.id());
            }
            run=runs.enforceDeadline(run.id()); // A slow observation cannot extend the absolute budget.
            if(run.status()==RunLifecycle.Status.STOPPING && run.workerUid()!=null && run.operations().stream().anyMatch(op->op.kind().equals("SUBMIT") && op.status().equals("CONFIRMED")))
                workers.dispatchPendingStop(run.id()); // Existing receipt prevents resend after restart/uncertainty.
            if(run.status().terminal()) {
                boolean saved=false;
                try{if(run.workerUid()!=null){results.collect(run.id());saved=true;}}catch(RuntimeException missing){if(recovery==null)throw missing;}
                if(recovery==null)outcome=run.workerUid()==null?NO_WORKER:RESULT_SAVED;
                else if(recovery.cleanup(run.id(),saved))outcome=saved?RESULT_SAVED:run.workerUid()==null?NO_WORKER:CLEANED_NO_RESULT;
            } else outcome="UNREACHABLE".equals(run.workerState())?UNAVAILABLE:WAITING;
        } catch(RuntimeException unavailable) {
            // Keep only a fixed outcome. Secrets/URLs/SDK response bodies must not enter status or logs.
            // A durable stop intent survives denied credentials, offline APIs, stale revisions and restart.
        } finally {queue.finish(claim,outcome);}
        return true;
    }
}
