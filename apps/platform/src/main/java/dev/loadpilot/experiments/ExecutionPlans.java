package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import jakarta.validation.Validator;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal plan persistence. No browser policy input or execution authorization endpoint exists. */
@Service
public class ExecutionPlans {
    public record Saved(UUID id,UUID runId,UUID attemptId,UUID submitOperationId,long runRevision,
                        String sha256,String canonicalJson,String preparedBy,OffsetDateTime createdAt) {}
    private final JdbcTemplate db;
    private final RunIntents runs;
    private final ExperimentSnapshots.Store snapshots;
    private final K6PlanEnvelope envelopes;
    private final ObjectMapper json;
    public ExecutionPlans(JdbcTemplate db,RunIntents runs,ExperimentSnapshots.Store snapshots,ObjectMapper json,Validator validator) {
        this.db=db;this.runs=runs;this.snapshots=snapshots;this.json=json;this.envelopes=new K6PlanEnvelope(json,validator);
    }
    private K6PlanEnvelope.Prepared current(UUID runId,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target) {
        return current(runId,policy,target,null);
    }
    private K6PlanEnvelope.Prepared current(UUID runId,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target,ExecutionClusterClients.Binding binding) {
        var run=runs.get(runId);
        if(run.status()!=RunLifecycle.Status.SUBMITTING) throw new ExperimentDrafts.Problem(409,"This run cannot prepare or dispatch a worker plan.");
        var submit=run.operations().stream().filter(op->op.kind().equals("SUBMIT")).findFirst().orElseThrow();
        if(!submit.status().equals("PENDING")) throw new ExperimentDrafts.Problem(409,"The submission intent is no longer pending.");
        runs.verifyDispatchScope(runId);
        var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),run.revision(),run.attemptId(),run.shardId(),submit.id(),run.workerName(),run.maximumSeconds(),run.deadlineAt(),runs.submissionStartedAt(runId));
        return envelopes.prepare(snapshots.get(run.snapshotId()),ticket,policy,target,binding,Instant.now());
    }
    @Transactional public K6PlanEnvelope.Prepared preview(UUID runId,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target) {
        return current(runId,policy,target);
    }
    @Transactional public K6PlanEnvelope.Prepared preview(UUID runId,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target,ExecutionClusterClients.Binding binding) {return current(runId,policy,target,binding);}
    private Saved find(UUID runId) {
        return db.query("SELECT * FROM experiment_execution_plan WHERE run_id=?",(row,index)->{
            String text=row.getString("plan_json"),hash=row.getString("sha256");
            if(!K6PlanEnvelope.hash(text).equals(hash)) throw new ExperimentDrafts.Problem(503,"Stored execution plan integrity verification failed.");
            try {
                var body=json.readTree(text);
                String schema=body.path("schemaVersion").asText();
                if(!(schema.equals("k6-execution-plan-v1") || (schema.equals("k6-execution-plan-v2") && body.path("clusterBinding").isObject())) || body.path("submissionReady").asBoolean(true)
                        || !body.path("runId").asText().equals(row.getString("run_id"))
                        || !body.path("attemptId").asText().equals(row.getString("attempt_id"))
                        || !body.path("submitOperationId").asText().equals(row.getString("submit_operation_id"))
                        || body.path("runRevision").asLong()!=row.getLong("run_revision"))
                    throw new ExperimentDrafts.Problem(503,"Stored execution plan identity verification failed.");
            } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new ExperimentDrafts.Problem(503,"Stored execution plan is unreadable."); }
            return new Saved(row.getObject("id",UUID.class),row.getObject("run_id",UUID.class),row.getObject("attempt_id",UUID.class),
                    row.getObject("submit_operation_id",UUID.class),row.getLong("run_revision"),hash,text,row.getString("prepared_by"),row.getObject("created_at",OffsetDateTime.class));
        },runId).stream().findFirst().orElse(null);
    }
    @Transactional(readOnly=true) public Saved get(UUID runId) {
        var saved=find(runId);if(saved==null) throw new ExperimentDrafts.Problem(404,"Execution plan not found.");return saved;
    }
    @Transactional(readOnly=true) public ExecutionClusterClients.Binding clusterBinding(UUID runId) {
        var saved=get(runId);
        try {
            var body=json.readTree(saved.canonicalJson());
            if(!body.path("schemaVersion").asText().equals("k6-execution-plan-v2")) throw new ExperimentDrafts.Problem(409,"This legacy plan has no frozen cluster binding. Review new inputs before live access.");
            var binding=json.treeToValue(body.path("clusterBinding"),ExecutionClusterClients.Binding.class);var run=runs.get(runId);
            if(binding==null || !run.targetId().equals(binding.targetId()) || binding.connectionRevision()<1 || !binding.namespace().equals(body.path("policy").path("namespace").asText())) throw new IllegalArgumentException();
            return binding;
        } catch(ExperimentDrafts.Problem known) {throw known;}
        catch(Exception invalid) {throw new ExperimentDrafts.Problem(503,"Saved cluster binding cannot be verified.");}
    }
    @Transactional(readOnly=true) public io.kubernetes.client.openapi.models.V1Job workerJob(RunIntents.Run run) {
        var saved=get(run.id());
        var submit=run.operations().stream().filter(op->op.kind().equals("SUBMIT")).findFirst().orElseThrow();
        try {
            var body=json.readTree(saved.canonicalJson());
            if(!saved.attemptId().equals(run.attemptId()) || !saved.submitOperationId().equals(submit.id())
                    || !run.snapshotId().toString().equals(body.path("snapshotId").asText()) || !run.workerName().equals(body.path("workerName").asText())) throw new IllegalArgumentException();
            var expected=io.kubernetes.client.openapi.JSON.<io.kubernetes.client.openapi.models.V1Job>deserialize(body.path("job").toString(),io.kubernetes.client.openapi.models.V1Job.class);
            if(expected==null || expected.getMetadata()==null || !run.workerName().equals(expected.getMetadata().getName())) throw new IllegalArgumentException();
            return expected;
        } catch(Exception invalid) { throw new ExperimentDrafts.Problem(503,"Saved worker plan cannot be verified."); }
    }
    @Transactional public Saved persist(UUID runId,String expectedHash,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target,String actor) {
        return persist(runId,expectedHash,policy,target,null,actor);
    }
    @Transactional public Saved persist(UUID runId,String expectedHash,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target,ExecutionClusterClients.Binding binding,String actor) {
        if(expectedHash==null || !expectedHash.matches("[a-f0-9]{64}")) throw new ExperimentDrafts.Problem(400,"Provide the exact execution-plan review hash.");
        if(db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId).isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
        var existing=find(runId);
        if(existing!=null) {
            if(!existing.sha256().equals(expectedHash)) throw new ExperimentDrafts.Problem(409,"This run already has a different execution plan.");
            return existing; // Historical retry only. verifyCurrent still rejects stopped/drifted dispatch.
        }
        var plan=current(runId,policy,target,binding);
        if(!plan.sha256().equals(expectedHash)) throw new ExperimentDrafts.Problem(409,"Execution policy, destination or run changed after plan review.");
        var run=runs.get(runId);var submit=run.operations().stream().filter(op->op.kind().equals("SUBMIT")).findFirst().orElseThrow();
        db.update("INSERT INTO experiment_execution_plan(id,run_id,attempt_id,submit_operation_id,run_revision,sha256,plan_json,prepared_by) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),runId,run.attemptId(),submit.id(),run.revision(),plan.sha256(),plan.canonicalJson(),actor);
        db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES (?,'EXECUTION_PLAN_PREPARED','EXPERIMENT',?,'SUCCESS')",actor,runId);
        return get(runId);
    }
    @Transactional public Saved verifyCurrent(UUID runId,String expectedHash,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target) {
        return verifyCurrent(runId,expectedHash,policy,target,null);
    }
    @Transactional public Saved verifyCurrent(UUID runId,String expectedHash,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target,ExecutionClusterClients.Binding binding) {
        if(db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR SHARE",UUID.class,runId).isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
        var saved=get(runId);var plan=current(runId,policy,target,binding);
        if(!saved.sha256().equals(expectedHash) || !saved.sha256().equals(plan.sha256())) throw new ExperimentDrafts.Problem(409,"Saved execution plan differs from current dispatch inputs.");
        return saved; // Binding/integrity only: submissionReady remains false and runtime authorization is separate.
    }
    @Transactional public Saved verifyPrepared(UUID runId,String expectedHash,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target,ExecutionClusterClients.Binding binding) {
        if(db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR SHARE",UUID.class,runId).isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
        var saved=get(runId);var run=runs.get(runId);
        if(run.status()!=RunLifecycle.Status.SUBMITTING || run.operations().stream().noneMatch(op->op.id().equals(saved.submitOperationId()) && op.kind().equals("SUBMIT") && !op.status().equals("CANCELLED")))
            throw new ExperimentDrafts.Problem(409,"This run cannot activate a prepared worker.");
        runs.verifyDispatchScope(runId);workerJob(run);
        // Observation revisions do not change reviewed inputs. Reconstruct with the original frozen revision.
        var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),saved.runRevision(),run.attemptId(),run.shardId(),saved.submitOperationId(),run.workerName(),run.maximumSeconds(),run.deadlineAt(),runs.submissionStartedAt(runId));
        var current=envelopes.prepare(snapshots.get(run.snapshotId()),ticket,policy,target,binding,Instant.now());
        if(!saved.sha256().equals(expectedHash) || !saved.sha256().equals(current.sha256())) throw new ExperimentDrafts.Problem(409,"Saved activation plan differs from current reviewed inputs.");
        return saved;
    }
}
