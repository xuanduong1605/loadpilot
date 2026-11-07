package dev.loadpilot.experiments;

import dev.loadpilot.scenarios.ScenarioStore;
import dev.loadpilot.targets.TargetStore;
import jakarta.validation.constraints.*;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable orchestration state only. No external execution or scheduler lives here. */
@Service
public class RunIntents {
    public record Start(@NotNull UUID snapshotId, @NotNull UUID requestKey,
                        @NotNull @Pattern(regexp="[0-9a-f]{64}") String snapshotHash,
                        @NotNull @AssertTrue Boolean acknowledged) {}
    public record Operation(UUID id, String kind, String status) {}
    public record Run(UUID id, UUID snapshotId, UUID targetId, UUID requestKey, RunLifecycle.Status status,
                      long revision, OffsetDateTime deadlineAt, UUID attemptId, UUID shardId, String workerName,
                      int maximumSeconds, List<Operation> operations, String verdict, String workerUid, String workerState) {}
    private final JdbcTemplate db;
    private final ExperimentSnapshots.Store snapshots;
    private final ScenarioStore scenarios;
    private final TargetStore targets;
    private final Validator validator;
    public RunIntents(JdbcTemplate db, ExperimentSnapshots.Store snapshots, ScenarioStore scenarios, TargetStore targets, Validator validator) {
        this.db = db; this.snapshots = snapshots; this.scenarios = scenarios; this.targets = targets; this.validator=validator;
    }
    @Transactional(readOnly=true) public Run get(UUID id) {
        var rows = db.query("SELECT r.*,a.id attempt_id,a.shard_id,a.worker_name,a.maximum_seconds,a.worker_uid,a.worker_state FROM experiment_run r JOIN experiment_attempt a ON a.run_id=r.id WHERE r.id=? AND a.attempt_number=1",
                (row, index) -> new Run(row.getObject("id", UUID.class), row.getObject("snapshot_id", UUID.class), row.getObject("target_id", UUID.class),
                        row.getObject("request_key", UUID.class), RunLifecycle.Status.valueOf(row.getString("status")), row.getLong("revision"),
                        row.getObject("deadline_at", OffsetDateTime.class), row.getObject("attempt_id", UUID.class), row.getObject("shard_id", UUID.class),
                        row.getString("worker_name"), row.getInt("maximum_seconds"), List.of(), "UNEVALUATED", row.getString("worker_uid"), row.getString("worker_state")), id);
        if (rows.isEmpty()) throw new ExperimentDrafts.Problem(404, "Experiment run not found.");
        var run = rows.getFirst();
        var operations = db.query("SELECT * FROM experiment_operation WHERE attempt_id=? ORDER BY created_at,id",
                (row, index) -> new Operation(row.getObject("id", UUID.class), row.getString("kind"), row.getString("status")), run.attemptId());
        return new Run(run.id(), run.snapshotId(), run.targetId(), run.requestKey(), run.status(), run.revision(), run.deadlineAt(), run.attemptId(), run.shardId(), run.workerName(), run.maximumSeconds(), operations, "UNEVALUATED", run.workerUid(), run.workerState());
    }
    /** Internal reservation for a future preflighted adapter. API cannot invoke it while no adapter exists. */
    @Transactional public Run reserve(Start request, String actor) {
        if (request == null || !validator.validate(request).isEmpty()) throw new ExperimentDrafts.Problem(400, "Provide a valid snapshot, hash, start key and acknowledgement.");
        if (!Boolean.TRUE.equals(request.acknowledged())) throw new ExperimentDrafts.Problem(400, "Acknowledge the exact reviewed inputs before starting.");
        // Global request-key lock and per-target lock serialize conflicting reservations without external effects.
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0)) IS NULL", Boolean.class, request.requestKey().toString());
        var prior = db.query("SELECT id,snapshot_id FROM experiment_run WHERE request_key=?", (row,index) -> new UUID[]{row.getObject("id",UUID.class),row.getObject("snapshot_id",UUID.class)}, request.requestKey());
        var snapshot = snapshots.get(request.snapshotId());
        if (!snapshot.sha256().equals(request.snapshotHash())) throw new ExperimentDrafts.Problem(409, "Snapshot hash does not match the reviewed inputs.");
        if (!prior.isEmpty()) {
            if (!prior.getFirst()[1].equals(request.snapshotId())) throw new ExperimentDrafts.Problem(409, "This start key belongs to different inputs.");
            return get(prior.getFirst()[0]);
        }
        var input = snapshot.inputs();
        if (!"experiment-input-v5".equals(input.schemaVersion()) || !"SINGLE_RUN".equals(input.mode()))
            throw new ExperimentDrafts.Problem(400, "This execution slice requires a current single-run snapshot.");
        db.queryForObject("SELECT id FROM registered_target WHERE id=? FOR UPDATE", UUID.class, input.target().id());
        var existing = db.queryForList("SELECT id FROM experiment_run WHERE snapshot_id=?", UUID.class, snapshot.id());
        if (!existing.isEmpty()) throw new ExperimentDrafts.Problem(409, "This snapshot already has a run. Retry its original start key or review a new draft revision.");
        verifyScope(input);
        if (db.queryForObject("SELECT count(*) FROM experiment_run WHERE target_id=? AND status IN ('QUEUED','SUBMITTING','RUNNING','STOPPING','RECOVERY_REQUIRED')", Integer.class, input.target().id()) != 0)
            throw new ExperimentDrafts.Problem(409, "This target already has an active or unresolved run.");
        UUID id=UUID.randomUUID(), attempt=UUID.randomUUID(), shard=UUID.randomUUID();
        db.update("INSERT INTO experiment_run(id,snapshot_id,target_id,request_key,status,deadline_at,created_by) VALUES (?,?,?,?,'QUEUED',CURRENT_TIMESTAMP + ? * INTERVAL '1 second',?)",
                id, snapshot.id(), input.target().id(), request.requestKey(), input.settings().totalBudgetSeconds(), actor);
        db.update("INSERT INTO experiment_attempt(id,run_id,attempt_number,shard_id,worker_name,maximum_seconds) VALUES (?,?,1,?,?,?)", attempt,id,shard,"lp-"+attempt,input.maximumAttemptSeconds());
        db.update("INSERT INTO experiment_operation(id,attempt_id,kind,status) VALUES (?,?,'SUBMIT','PENDING')", UUID.randomUUID(),attempt);
        audit(actor,"RUN_RESERVED",id);
        return get(id);
    }
    private void verifyScope(ExperimentSnapshots.Inputs input) {
        var target = targets.forReview(input.target().id());
        if (target.archivedAt() != null || target.revision() != input.target().revision()) throw new ExperimentDrafts.Problem(409, "Target changed after review. Review a new snapshot.");
        var script = input.scenario().script();
        var approved = scenarios.forReview(script.scenarioId(), script.version());
        if (!approved.approvalId().equals(input.scenario().approvalId()) || !approved.script().sha256().equals(script.sha256()))
            throw new ExperimentDrafts.Problem(409, "Script approval changed after review.");
    }
    @Transactional public void verifyDispatchScope(UUID runId) {
        verifyScope(snapshots.get(get(runId).snapshotId()).inputs());
    }
    @Transactional(readOnly=true) public void verifySnapshotScope(UUID snapshotId){verifyScope(snapshots.get(snapshotId).inputs());}
    private Run lock(UUID id) {
        var ids=db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,id);
        if(ids.isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
        return get(id);
    }
    @Transactional(readOnly=true) public UUID findRequest(UUID key){return db.queryForList("SELECT id FROM experiment_run WHERE request_key=?",UUID.class,key).stream().findFirst().orElse(null);}
    @Transactional public Run abortExecution(UUID id){
        var run=lock(id);if(run.status().terminal() || run.status()==RunLifecycle.Status.STOPPING)return run;
        db.update("UPDATE experiment_run SET status='STOPPING',stop_reason='EXECUTION',revision=revision+1,updated_at=clock_timestamp() WHERE id=?",id);
        db.update("INSERT INTO experiment_operation(id,attempt_id,kind,status) VALUES (?,?,'STOP','PENDING') ON CONFLICT (attempt_id,kind) DO NOTHING",UUID.randomUUID(),run.attemptId());
        audit("start-coordinator","RUN_EXECUTION_ABORT_REQUESTED",id);return get(id);
    }
    @Transactional public Run claimSubmission(UUID id) {
        var run=lock(id);
        if(run.status()!=RunLifecycle.Status.QUEUED) throw new ExperimentDrafts.Problem(409,"This run cannot be submitted.");
        if (Boolean.TRUE.equals(db.queryForObject("SELECT deadline_at < clock_timestamp() + ? * INTERVAL '1 second' FROM experiment_run WHERE id=?",Boolean.class,run.maximumSeconds(),id)))
            throw new ExperimentDrafts.Problem(409,"Run budget expired before submission.");
        verifyScope(snapshots.get(run.snapshotId()).inputs());
        db.update("UPDATE experiment_attempt SET submission_started_at=clock_timestamp() WHERE id=? AND submission_started_at IS NULL",run.attemptId());
        db.update("UPDATE experiment_run SET status='SUBMITTING',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        return get(id); // Commit this state before any adapter call; failure is uncertain, not permission to resubmit.
    }
    @Transactional(readOnly=true) public OffsetDateTime submissionStartedAt(UUID id) {
        var run=get(id);
        var started=db.queryForObject("SELECT submission_started_at FROM experiment_attempt WHERE id=?",OffsetDateTime.class,run.attemptId());
        if(started==null) throw new ExperimentDrafts.Problem(409,"This legacy attempt has no submission clock. Reconcile it before reviewing new execution.");
        return started;
    }
    /** Call after observation so confirmed startup is preserved across a later inspection outage. */
    @Transactional public Run enforceStartup(UUID id) {
        var run=lock(id);
        if(run.status().terminal() || run.status()==RunLifecycle.Status.QUEUED || run.status()==RunLifecycle.Status.STOPPING) return run;
        int seconds=snapshots.get(run.snapshotId()).inputs().executionBounds().startupTimeoutSeconds();
        boolean expired=Boolean.TRUE.equals(db.queryForObject("SELECT submission_started_at + ? * INTERVAL '1 second' <= clock_timestamp() AND startup_observed_at IS NULL FROM experiment_attempt WHERE id=?",Boolean.class,seconds,run.attemptId()));
        if(!expired) return run;
        db.update("UPDATE experiment_run SET status='STOPPING',stop_reason='STARTUP',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        db.update("INSERT INTO experiment_operation(id,attempt_id,kind,status) VALUES (?,?,'STOP','PENDING') ON CONFLICT (attempt_id,kind) DO NOTHING",UUID.randomUUID(),run.attemptId());
        audit("run-monitor","RUN_STARTUP_UNCONFIRMED",id);
        return get(id);
    }
    @Transactional public Run stop(UUID id,String actor) {
        var run=lock(id); var next=run.status().requestStop();
        if(next==run.status()) return run;
        db.update("UPDATE experiment_run SET status=?,stop_reason='USER',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",next.name(),id);
        if(next==RunLifecycle.Status.CANCELLED)
            db.update("UPDATE experiment_operation SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND kind='SUBMIT'",run.attemptId());
        else db.update("INSERT INTO experiment_operation(id,attempt_id,kind,status) VALUES (?,?,'STOP','PENDING') ON CONFLICT (attempt_id,kind) DO NOTHING",UUID.randomUUID(),run.attemptId());
        audit(actor,"RUN_STOP_REQUESTED",id);
        return get(id);
    }
    /** Uses the database clock; a missed deadline never proves that a worker terminated. */
    @Transactional public Run enforceDeadline(UUID id) {
        var run=lock(id);
        if(run.status().terminal() || run.status()==RunLifecycle.Status.STOPPING ||
                !Boolean.TRUE.equals(db.queryForObject("SELECT deadline_at <= clock_timestamp() FROM experiment_run WHERE id=?",Boolean.class,id))) return run;
        var next=run.status()==RunLifecycle.Status.QUEUED?RunLifecycle.Status.FAILED:RunLifecycle.Status.STOPPING;
        db.update("UPDATE experiment_run SET status=?,stop_reason='DEADLINE',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",next.name(),id);
        if(next==RunLifecycle.Status.FAILED)
            db.update("UPDATE experiment_operation SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND kind='SUBMIT'",run.attemptId());
        else db.update("INSERT INTO experiment_operation(id,attempt_id,kind,status) VALUES (?,?,'STOP','PENDING') ON CONFLICT (attempt_id,kind) DO NOTHING",UUID.randomUUID(),run.attemptId());
        audit("run-monitor","RUN_DEADLINE_EXPIRED",id);
        return get(id);
    }
    private void audit(String actor,String action,UUID id) {
        db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES (?,?,'EXPERIMENT',?,'SUCCESS')",actor,action,id);
    }
}
