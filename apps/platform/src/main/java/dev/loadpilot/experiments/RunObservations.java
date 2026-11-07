package dev.loadpilot.experiments;

import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal trusted-observer boundary. Never exposed as a browser-supplied status update. */
@Service
public class RunObservations {
    public record Observation(@NotNull UUID id, @NotNull UUID attemptId, @NotNull UUID submitOperationId,
                              @Positive long expectedRevision, @NotBlank @Size(max=63) String workerName,
                              @Pattern(regexp="[A-Za-z0-9._:-]{1,128}") String workerUid,
                              @NotNull RunLifecycle.WorkerState state, boolean allWorkersTerminated) {}
    private final JdbcTemplate db;
    private final RunIntents runs;
    private final Validator validator;
    public RunObservations(JdbcTemplate db, RunIntents runs, Validator validator) {
        this.db=db;this.runs=runs;this.validator=validator;
    }
    @Transactional public RunIntents.Run record(UUID runId, Observation input) {
        if (input==null || !validator.validate(input).isEmpty()) throw new ExperimentDrafts.Problem(400,"Provide a complete worker observation.");
        boolean terminalState=input.state()==RunLifecycle.WorkerState.SUCCEEDED || input.state()==RunLifecycle.WorkerState.FAILED || input.state()==RunLifecycle.WorkerState.TERMINATED;
        if ((input.state()!=RunLifecycle.WorkerState.UNREACHABLE && input.workerUid()==null) || (input.allWorkersTerminated() && !terminalState))
            throw new ExperimentDrafts.Problem(400,"Worker identity and termination evidence are inconsistent.");
        var locked=db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId);
        if(locked.isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
        var prior=db.query("SELECT * FROM experiment_worker_observation WHERE id=?",(row,index)->new Observation(
                row.getObject("id",UUID.class),row.getObject("attempt_id",UUID.class),row.getObject("submit_operation_id",UUID.class),
                row.getLong("expected_revision"),row.getString("worker_name"),row.getString("worker_uid"),
                RunLifecycle.WorkerState.valueOf(row.getString("state")),row.getBoolean("all_workers_terminated")),input.id());
        var run=runs.get(runId);
        if(!prior.isEmpty()) {
            if(!prior.getFirst().equals(input) || !run.attemptId().equals(input.attemptId())) throw new ExperimentDrafts.Problem(409,"Observation identity was reused for different evidence.");
            return run;
        }
        if(run.revision()!=input.expectedRevision()) throw new ExperimentDrafts.Problem(409,"Run changed after the observation began. Reobserve the worker.");
        var submit=run.operations().stream().filter(op->op.kind().equals("SUBMIT")).findFirst().orElseThrow();
        if(!run.attemptId().equals(input.attemptId()) || !run.workerName().equals(input.workerName()) || !submit.id().equals(input.submitOperationId()))
            throw new ExperimentDrafts.Problem(409,"Observation does not match this attempt and submission intent.");
        if(run.workerUid()!=null && !Objects.equals(run.workerUid(),input.workerUid()))
            throw new ExperimentDrafts.Problem(409,"Worker UID changed. Keep recovery protection and reconcile the worker identity.");
        var next=run.status().observe(input.state(),input.allWorkersTerminated());
        if(next==RunLifecycle.Status.CANCELLED && java.util.Set.of("DEADLINE","STARTUP","EXECUTION").contains(java.util.Objects.toString(db.queryForObject("SELECT stop_reason FROM experiment_run WHERE id=?",String.class,runId),"")))
            next=RunLifecycle.Status.FAILED;
        if(db.update("INSERT INTO experiment_worker_observation(id,run_id,attempt_id,submit_operation_id,expected_revision,worker_name,worker_uid,state,all_workers_terminated) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                input.id(),runId,input.attemptId(),input.submitOperationId(),input.expectedRevision(),input.workerName(),input.workerUid(),input.state().name(),input.allWorkersTerminated())!=1)
            throw new ExperimentDrafts.Problem(409,"Observation identity was reused for different evidence.");
        db.update("UPDATE experiment_attempt SET worker_uid=COALESCE(worker_uid,?),worker_state=? WHERE id=?",input.workerUid(),input.state().name(),run.attemptId());
        if(input.state()==RunLifecycle.WorkerState.RUNNING)
            db.update("UPDATE experiment_attempt SET startup_observed_at=COALESCE(startup_observed_at,clock_timestamp()) WHERE id=?",run.attemptId());
        db.update("UPDATE experiment_run SET status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",next.name(),runId);
        if(input.state()!=RunLifecycle.WorkerState.UNREACHABLE)
            db.update("UPDATE experiment_operation SET status='CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE id=?",submit.id());
        if(next==RunLifecycle.Status.CANCELLED || (next==RunLifecycle.Status.FAILED && run.status()==RunLifecycle.Status.STOPPING))
            db.update("UPDATE experiment_operation SET status='CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND kind='STOP'",run.attemptId());
        db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES ('worker-observer','WORKER_OBSERVED','EXPERIMENT',?,'SUCCESS')",runId);
        return runs.get(runId);
    }
}
