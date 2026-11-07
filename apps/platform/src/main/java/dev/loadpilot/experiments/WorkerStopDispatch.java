package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.KubernetesWorkerStopper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal one-shot stop coordinator; caller must supply an authorized, scoped stopper. */
@Service
public class WorkerStopDispatch {
    public record Receipt(UUID operationId,UUID runId,String jobUid,String outcome,OffsetDateTime requestedAt,OffsetDateTime acknowledgedAt) {}
    public record Claim(KubernetesWorkerStopper.Command command,Receipt receipt,boolean dispatch) {}
    public record Result(RunIntents.Run run,Receipt receipt) {}
    private final Receipts receipts;
    private final RunIntents runs;
    public WorkerStopDispatch(Receipts receipts,RunIntents runs) { this.receipts=receipts;this.runs=runs; }
    @Transactional(propagation=Propagation.NEVER)
    public Result request(UUID runId,KubernetesWorkerStopper stopper) {
        var claim=receipts.claim(runId); // Separate transaction commits before the external callback.
        if(!claim.dispatch()) return new Result(runs.get(runId),claim.receipt());
        String outcome;
        try { outcome=stopper.request(claim.command()).name(); }
        catch(KubernetesWorkerStopper.Problem failure) { outcome=failure.status()==409?"UID_CONFLICT":failure.status()==403?"DENIED":"UNCERTAIN"; }
        var saved=receipts.finish(claim.receipt().operationId(),outcome);
        return new Result(runs.get(runId),saved); // ACK never confirms STOP or releases the active-run guard.
    }
    @Service
    public static class Receipts {
        private final JdbcTemplate db;private final RunIntents runs;private final ExecutionPlans plans;
        public Receipts(JdbcTemplate db,RunIntents runs,ExecutionPlans plans) {this.db=db;this.runs=runs;this.plans=plans;}
        public Receipt get(UUID operationId) {
            return db.query("SELECT * FROM experiment_worker_stop_receipt WHERE operation_id=?",(row,index)->new Receipt(row.getObject("operation_id",UUID.class),row.getObject("run_id",UUID.class),row.getString("job_uid"),row.getString("outcome"),row.getObject("requested_at",OffsetDateTime.class),row.getObject("acknowledged_at",OffsetDateTime.class)),operationId).stream().findFirst().orElse(null);
        }
        @Transactional public Claim claim(UUID runId) {
            if(db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId).isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
            var run=runs.get(runId);
            if(run.status()!=RunLifecycle.Status.STOPPING || run.workerUid()==null || !run.workerUid().matches("[A-Za-z0-9._:-]{1,128}")) throw new ExperimentDrafts.Problem(409,"Stop dispatch requires a stopping run with a bound worker UID.");
            if(run.operations().stream().noneMatch(op->op.kind().equals("SUBMIT") && op.status().equals("CONFIRMED"))) throw new ExperimentDrafts.Problem(409,"Reconcile the uncertain submission before dispatching stop.");
            var stop=run.operations().stream().filter(op->op.kind().equals("STOP") && op.status().equals("PENDING")).findFirst().orElseThrow(()->new ExperimentDrafts.Problem(409,"A pending durable stop intent is required."));
            var existing=get(stop.id());
            if(existing!=null) {
                if(!existing.runId().equals(runId) || !existing.jobUid().equals(run.workerUid())) throw new ExperimentDrafts.Problem(503,"Stop receipt identity cannot be verified.");
                return new Claim(null,existing,false); // Includes crash/uncertain REQUESTED: reconcile, do not blindly resend.
            }
            var job=plans.workerJob(run);
            var command=new KubernetesWorkerStopper.Command(job.getMetadata().getNamespace(),run.workerName(),run.workerUid(),stop.id());
            db.update("INSERT INTO experiment_worker_stop_receipt(operation_id,run_id,job_uid,outcome) VALUES (?,?,?,'REQUESTED')",stop.id(),runId,run.workerUid());
            db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES ('worker-stopper','WORKER_STOP_DISPATCH_INTENT','EXPERIMENT',?,'SUCCESS')",stop.id());
            return new Claim(command,get(stop.id()),true);
        }
        @Transactional public Receipt finish(UUID operationId,String outcome) {
            if(!java.util.Set.of("ACCEPTED","NOT_FOUND","UNCERTAIN","UID_CONFLICT","DENIED").contains(outcome)) throw new IllegalArgumentException("Invalid stop acknowledgement.");
            if(db.update("UPDATE experiment_worker_stop_receipt SET outcome=?,acknowledged_at=CURRENT_TIMESTAMP WHERE operation_id=? AND outcome='REQUESTED'",outcome,operationId)==1)
                db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES ('worker-stopper','WORKER_STOP_ACK_RECORDED','EXPERIMENT',?,'SUCCESS')",operationId);
            return get(operationId);
        }
    }
}
