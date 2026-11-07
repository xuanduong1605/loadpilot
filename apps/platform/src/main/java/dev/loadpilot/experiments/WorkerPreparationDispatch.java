package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal suspended-resource coordinator. No activation, automatic retry or public execution route. */
@Service
public class WorkerPreparationDispatch {
    public record Receipt(UUID id,UUID planId,String planHash,Resource resource,String namespace,String workerName,
                          long runRevision,String outcome,String resourceUid,OffsetDateTime requestedAt,OffsetDateTime acknowledgedAt) {}
    public record Claim(ExecutionPlans.Saved plan,Receipt receipt,boolean dispatch) {}
    public record Result(RunIntents.Run run,Receipt receipt) {}
    @FunctionalInterface public interface Creator {
        KubernetesWorkerPreparer.Receipt create(K6PlanEnvelope.Prepared plan,UUID submitOperationId,Resource resource);
    }
    private final Receipts receipts;
    private final RunIntents runs;
    private final ExecutionPlans plans;
    private final ExecutionPreflight preflight;
    private final ObjectMapper json;
    public WorkerPreparationDispatch(Receipts receipts,RunIntents runs,ExecutionPlans plans,ExecutionPreflight preflight,ObjectMapper json) {
        this.receipts=receipts;this.runs=runs;this.plans=plans;this.preflight=preflight;this.json=json;
    }
    @Transactional(propagation=Propagation.NEVER)
    public Result request(UUID runId,String expectedHash,Resource resource,KubernetesWorkerPreparer preparer) {
        if(preparer==null) throw new IllegalArgumentException("An authorized preparation client is required.");
        return execute(runId,expectedHash,resource,preparer::create);
    }
    @Transactional(propagation=Propagation.NEVER)
    public Result execute(UUID runId,String expectedHash,Resource resource,Creator creator) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Worker preparation must begin outside an existing transaction.");
        if(resource==null || creator==null) throw new IllegalArgumentException("A preparation resource and authorized client are required.");
        var saved=plans.get(runId);requireHash(saved,expectedHash);
        var previous=receipts.get(saved.id(),resource);
        if(previous!=null) {verifyReceipt(previous,saved,json);return new Result(runs.get(runId),previous);} // Historical ACK or uncertain/crash intent: no new I/O.
        K6JobPlan.PinnedTarget pin;
        try {pin=json.treeToValue(json.readTree(saved.canonicalJson()).path("destination"),K6JobPlan.PinnedTarget.class);}
        catch(Exception invalid) {throw new ExperimentDrafts.Problem(503,"Saved preparation destination cannot be verified.");}
        preflight.verifySaved(runId,expectedHash,pin); // DNS/provider checks outside the claim transaction.
        var claim=receipts.claim(runId,saved.id(),expectedHash,resource); // Commits before external I/O.
        if(!claim.dispatch()) return new Result(runs.get(runId),claim.receipt());
        String outcome;String uid=null;
        try {
            var ack=creator.create(new K6PlanEnvelope.Prepared(claim.plan().canonicalJson(),claim.plan().sha256()),claim.plan().submitOperationId(),resource);
            var expected=claim.receipt();
            if(ack==null || ack.resource()!=resource || !expected.namespace().equals(ack.namespace()) || !expected.workerName().equals(ack.name())
                    || ack.uid()==null || !ack.uid().matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalStateException("Invalid preparation acknowledgement.");
            outcome="ACKNOWLEDGED";uid=ack.uid();
        } catch(KubernetesWorkerPreparer.Problem failure) {outcome=failure.status()==409?"CONFLICT":failure.status()==403?"DENIED":"UNCERTAIN";}
        catch(RuntimeException failure) {outcome="UNCERTAIN";}
        var receipt=receipts.finish(claim.receipt().id(),outcome,uid);
        return new Result(runs.get(runId),receipt); // Never confirms SUBMIT, changes run status or activates a Job.
    }
    private static void requireHash(ExecutionPlans.Saved saved,String expectedHash) {
        if(expectedHash==null || !saved.sha256().equals(expectedHash)) throw new ExperimentDrafts.Problem(409,"Preparation requires the exact saved plan hash.");
    }
    private static void verifyReceipt(Receipt receipt,ExecutionPlans.Saved saved,ObjectMapper json) {
        try {
            var body=json.readTree(saved.canonicalJson());
            if(!receipt.planId().equals(saved.id()) || !receipt.planHash().equals(saved.sha256()) || receipt.runRevision()!=saved.runRevision()
                    || !receipt.namespace().equals(body.path("clusterBinding").path("namespace").asText()) || !receipt.workerName().equals(body.path("workerName").asText())) throw new IllegalArgumentException();
        } catch(Exception invalid) {throw new ExperimentDrafts.Problem(503,"Preparation receipt identity cannot be verified.");}
    }
    @Service
    public static class Receipts {
        private final JdbcTemplate db;private final RunIntents runs;private final ExecutionPlans plans;private final ObjectMapper json;
        public Receipts(JdbcTemplate db,RunIntents runs,ExecutionPlans plans,ObjectMapper json) {this.db=db;this.runs=runs;this.plans=plans;this.json=json;}
        private Receipt read(java.sql.ResultSet row,int index) throws java.sql.SQLException {
            return new Receipt(row.getObject("id",UUID.class),row.getObject("plan_id",UUID.class),row.getString("plan_sha256"),Resource.valueOf(row.getString("resource")),
                    row.getString("namespace"),row.getString("worker_name"),row.getLong("run_revision"),row.getString("outcome"),row.getString("resource_uid"),row.getObject("requested_at",OffsetDateTime.class),row.getObject("acknowledged_at",OffsetDateTime.class));
        }
        @Transactional(readOnly=true) public Receipt get(UUID planId,Resource resource) {
            return db.query("SELECT * FROM experiment_worker_preparation WHERE plan_id=? AND resource=?",this::read,planId,resource.name()).stream().findFirst().orElse(null);
        }
        @Transactional public Claim claim(UUID runId,UUID planId,String expectedHash,Resource resource) {
            if(db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId).isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
            var saved=plans.get(runId);requireHash(saved,expectedHash);
            if(!saved.id().equals(planId)) throw new ExperimentDrafts.Problem(409,"Preparation plan identity changed.");
            var previous=get(saved.id(),resource);
            if(previous!=null) {verifyReceipt(previous,saved,json);return new Claim(saved,previous,false);}
            var run=runs.get(runId);
            if(run.status()!=RunLifecycle.Status.SUBMITTING || run.revision()!=saved.runRevision() || run.workerUid()!=null
                    || run.operations().stream().noneMatch(op->op.id().equals(saved.submitOperationId()) && op.kind().equals("SUBMIT") && op.status().equals("PENDING")))
                throw new ExperimentDrafts.Problem(409,"This run cannot prepare another worker resource.");
            runs.verifyDispatchScope(runId);
            if(Boolean.TRUE.equals(db.queryForObject("SELECT deadline_at < clock_timestamp() + ? * INTERVAL '1 second' FROM experiment_run WHERE id=?",Boolean.class,run.maximumSeconds(),runId)))
                throw new ExperimentDrafts.Problem(409,"Run budget expired before worker preparation.");
            String namespace;
            try {
                var body=json.readTree(saved.canonicalJson());
                if(!"k6-execution-plan-v2".equals(body.path("schemaVersion").asText()) || !Set.of("job-planner-v2","job-planner-v3","job-planner-v4","job-planner-v5","job-planner-v6").contains(body.path("plannerVersion").asText()) || !body.path("job").path("spec").path("suspend").asBoolean()) throw new IllegalArgumentException();
                namespace=plans.clusterBinding(runId).namespace();
                if(!run.workerName().equals(body.path("workerName").asText()) || !namespace.equals(body.path("job").path("metadata").path("namespace").asText())) throw new IllegalArgumentException();
            } catch(Exception invalid) {throw new ExperimentDrafts.Problem(503,"A bound suspended worker preparation plan is required.");}
            Resource prerequisite=switch(resource) {case FILES->null;case NETWORK->Resource.FILES;case JOB->Resource.NETWORK;};
            if(prerequisite!=null) {
                var prior=get(saved.id(),prerequisite);
                if(prior==null || !"ACKNOWLEDGED".equals(prior.outcome()) || !saved.sha256().equals(prior.planHash()))
                    throw new ExperimentDrafts.Problem(409,"A preceding worker resource still requires acknowledgement or reconciliation.");
                verifyReceipt(prior,saved,json);
            }
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO experiment_worker_preparation(id,plan_id,plan_sha256,resource,namespace,worker_name,run_revision,outcome) VALUES (?,?,?,?,?,?,?,'REQUESTED')",
                    id,saved.id(),saved.sha256(),resource.name(),namespace,run.workerName(),run.revision());
            audit("WORKER_PREPARATION_INTENT",id);
            return new Claim(saved,get(saved.id(),resource),true);
        }
        @Transactional public Receipt finish(UUID id,String outcome,String uid) {
            if(!Set.of("ACKNOWLEDGED","CONFLICT","DENIED","UNCERTAIN").contains(outcome) || (outcome.equals("ACKNOWLEDGED")!=(uid!=null)) || (uid!=null && !uid.matches("[A-Za-z0-9._:-]{1,128}")))
                throw new IllegalArgumentException("Invalid preparation acknowledgement.");
            var rows=db.query("SELECT * FROM experiment_worker_preparation WHERE id=? FOR UPDATE",this::read,id);
            if(rows.isEmpty()) throw new ExperimentDrafts.Problem(404,"Preparation intent not found.");
            var receipt=rows.getFirst();
            if(!receipt.outcome().equals("REQUESTED")) {
                if(!receipt.outcome().equals(outcome) || !Objects.equals(receipt.resourceUid(),uid)) throw new ExperimentDrafts.Problem(409,"Preparation intent already has different evidence.");
                return receipt;
            }
            db.update("UPDATE experiment_worker_preparation SET outcome=?,resource_uid=?,acknowledged_at=CURRENT_TIMESTAMP WHERE id=?",outcome,uid,id);
            audit("WORKER_PREPARATION_ACK",id);
            return get(receipt.planId(),receipt.resource());
        }
        private void audit(String action,UUID id) {db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES ('worker-preparer',?,'EXPERIMENT',?,'SUCCESS')",action,id);}
    }
}
