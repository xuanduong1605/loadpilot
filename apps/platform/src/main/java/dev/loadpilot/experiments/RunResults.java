package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.*;
import dev.loadpilot.integrations.k6.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable, sanitized whole-run output. Collection is internal; HTTP reads never query a cluster. */
@Service
public class RunResults {
    public record Evidence(UUID runId,UUID attemptId,UUID shardId,UUID planId,String planSha256,String jobUid,String podUid,
                           Instant startedAt,Instant finishedAt,int exitCode,String source,String scope,String availability,K6TerminalSummary.Summary summary) {}
    public record Saved(Evidence evidence,String sha256,OffsetDateTime recordedAt) {}
    public record View(UUID runId,String status,String verdict,String resultState,Saved result) {}
    private final JdbcTemplate db;private final RunIntents runs;private final ExecutionPlans plans;private final ObjectMapper json;
    public RunResults(JdbcTemplate db,RunIntents runs,ExecutionPlans plans,ObjectMapper json) {this.db=db;this.runs=runs;this.plans=plans;this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);}
    @Transactional(readOnly=true) public View read(UUID runId) {
        var run=runs.get(runId);var saved=find(runId);
        return new View(runId,run.status().name(),"UNEVALUATED",saved==null?run.status().terminal()?"NOT_COLLECTED":"WAITING":saved.evidence().availability(),saved);
    }
    @Transactional public Saved record(UUID runId,long revision,String jobUid,K6TerminalSummary.Collected collected) {
        if(db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId).isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
        var run=runs.get(runId);var plan=plans.get(runId);var job=plans.workerJob(run);
        if(!run.status().terminal() || !Objects.equals(jobUid,run.workerUid()) || jobUid==null
                || run.operations().stream().noneMatch(op->op.id().equals(plan.submitOperationId()) && op.kind().equals("SUBMIT") && op.status().equals("CONFIRMED")))
            throw new ExperimentDrafts.Problem(409,"A terminal run and confirmed bound worker are required for final results.");
        K6TerminalSummary.Summary summary;
        try {
            if(collected==null || collected.podUid()==null || !collected.podUid().matches("[A-Za-z0-9._:-]{1,128}") || collected.startedAt()==null || collected.finishedAt()==null
                    || collected.finishedAt().isBefore(collected.startedAt()) || collected.exitCode()<0 || collected.exitCode()>255
                    || collected.finishedAt().isAfter(Instant.now().plusSeconds(5)) || (run.status()==RunLifecycle.Status.COMPLETED && collected.exitCode()!=0)
                    || Duration.between(collected.startedAt(),collected.finishedAt()).compareTo(Duration.ofSeconds(run.maximumSeconds()+5L))>0) throw new IllegalArgumentException();
            var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),run.revision(),run.attemptId(),run.shardId(),plan.submitOperationId(),run.workerName(),run.maximumSeconds(),run.deadlineAt());
            summary=new K6TerminalSummary(json).parse(json.writeValueAsString(collected.summary()),ticket,job);
            if(summary.durationMs()!=null && summary.durationMs()>Duration.between(collected.startedAt(),collected.finishedAt()).toMillis()+1000) throw new IllegalArgumentException();
        } catch(Exception invalid) {throw new ExperimentDrafts.Problem(409,"Final result identity or numeric evidence cannot be verified.");}
        var evidence=new Evidence(run.id(),run.attemptId(),run.shardId(),plan.id(),plan.sha256(),jobUid,collected.podUid(),collected.startedAt(),collected.finishedAt(),collected.exitCode(),
                "k6 handleSummary / Kubernetes termination message","WHOLE_RUN_INCLUDING_WARMUP_NOT_SLO",summary.availability(),summary);
        String text=encode(evidence),hash=K6PlanEnvelope.hash(text);var prior=find(runId);
        if(prior!=null) {
            if(!prior.sha256().equals(hash) || !prior.evidence().equals(evidence)) throw new ExperimentDrafts.Problem(409,"This attempt already has different immutable result evidence.");
            return prior;
        }
        if(run.revision()!=revision) throw new ExperimentDrafts.Problem(409,"Run changed during final result collection. Reobserve before recording.");
        db.update("INSERT INTO experiment_worker_result(attempt_id,run_id,plan_id,job_uid,pod_uid,sha256,result_json) VALUES (?,?,?,?,?,?,?)",run.attemptId(),run.id(),plan.id(),jobUid,collected.podUid(),hash,text);
        db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES ('worker-results','WORKER_RESULT_RECORDED','EXPERIMENT',?,'SUCCESS')",run.id());
        return find(runId);
    }
    private Saved find(UUID runId) {
        var rows=db.query("SELECT * FROM experiment_worker_result WHERE run_id=?",(row,index)->{
            String text=row.getString("result_json"),hash=row.getString("sha256");
            try {
                if(!K6PlanEnvelope.hash(text).equals(hash)) throw new IllegalArgumentException();
                var evidence=json.readValue(text,Evidence.class);
                if(!runId.equals(evidence.runId()) || !row.getObject("attempt_id",UUID.class).equals(evidence.attemptId())
                        || !row.getObject("plan_id",UUID.class).equals(evidence.planId()) || !row.getString("job_uid").equals(evidence.jobUid()) || !row.getString("pod_uid").equals(evidence.podUid())) throw new IllegalArgumentException();
                return new Saved(evidence,hash,row.getObject("recorded_at",OffsetDateTime.class));
            } catch(Exception corrupt) {throw new ExperimentDrafts.Problem(503,"Saved result integrity could not be verified.");}
        },runId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private String encode(Evidence evidence) {
        try {return json.writeValueAsString(evidence);} catch(Exception impossible) {throw new IllegalStateException("Cannot serialize final result evidence.");}
    }
}
