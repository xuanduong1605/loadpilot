package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal activation boundary. Runtime/egress authorization remains a caller prerequisite. */
@Service
public class WorkerActivationDispatch {
    public record Receipt(UUID operationId,UUID planId,UUID runId,String planHash,String jobUid,String resourceVersion,
                          String patchJson,String patchHash,long runRevision,String outcome,String acknowledgedVersion,
                          OffsetDateTime requestedAt,OffsetDateTime acknowledgedAt) {}
    public record Inputs(ExecutionPlans.Saved plan,RunIntents.Run run,KubernetesWorkerActivator.Resources resources) {}
    public record Claim(Receipt receipt,boolean dispatch) {}
    public record Result(RunIntents.Run run,Receipt receipt) {}
    @FunctionalInterface public interface Inspector {KubernetesWorkerActivator.Prepared prepare(K6PlanEnvelope.Prepared plan,KubernetesWorkerActivator.Resources resources);}
    @FunctionalInterface public interface Writer {KubernetesWorkerActivator.Receipt activate(KubernetesWorkerActivator.Prepared command,UUID operationId);}
    private final RunIntents runs;private final ExecutionPlans plans;private final ExecutionPreflight preflight;private final Receipts receipts;private final ObjectMapper json;
    public WorkerActivationDispatch(RunIntents runs,ExecutionPlans plans,ExecutionPreflight preflight,Receipts receipts,ObjectMapper json) {this.runs=runs;this.plans=plans;this.preflight=preflight;this.receipts=receipts;this.json=json;}
    @Transactional(propagation=Propagation.NEVER)
    public Result activate(UUID runId,String expectedHash,Inspector inspector,Writer writer) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Worker activation must begin outside an existing transaction.");
        if(inspector==null || writer==null) throw new IllegalArgumentException("Authorized activation adapters are required.");
        var saved=plans.get(runId);requireHash(saved,expectedHash);var existing=receipts.get(saved.id());
        if(existing!=null) {verifyReceipt(saved,existing);return new Result(runs.get(runId),existing);}
        K6JobPlan.PinnedTarget pin;
        try {pin=json.treeToValue(json.readTree(saved.canonicalJson()).path("destination"),K6JobPlan.PinnedTarget.class);}
        catch(Exception invalid) {throw new ExperimentDrafts.Problem(503,"Saved activation destination cannot be verified.");}
        preflight.verifyPrepared(runId,expectedHash,pin);
        var inputs=receipts.inputs(runId,expectedHash);
        var command=inspector.prepare(new K6PlanEnvelope.Prepared(saved.canonicalJson(),saved.sha256()),inputs.resources());
        var claim=receipts.claim(inputs,command);
        if(!claim.dispatch()) return new Result(runs.get(runId),claim.receipt());
        String outcome;String version=null;
        try {
            var ack=writer.activate(command,claim.receipt().operationId());
            if(ack==null || !command.jobUid().equals(ack.jobUid()) || ack.resourceVersion()==null || ack.resourceVersion().isBlank() || ack.resourceVersion().length()>256 || command.resourceVersion().equals(ack.resourceVersion())) throw new IllegalStateException();
            outcome="ACKNOWLEDGED";version=ack.resourceVersion();
        } catch(KubernetesWorkerActivator.Problem failure) {outcome=failure.status()==409?"CONFLICT":failure.status()==403?"DENIED":"UNCERTAIN";}
        catch(RuntimeException failure) {outcome="UNCERTAIN";}
        var receipt=receipts.finish(claim.receipt().operationId(),outcome,version);
        return new Result(runs.get(runId),receipt);
    }
    private static void requireHash(ExecutionPlans.Saved saved,String hash) {if(hash==null || !saved.sha256().equals(hash)) throw new ExperimentDrafts.Problem(409,"Activation requires the exact saved plan hash.");}
    private static void verifyReceipt(ExecutionPlans.Saved saved,Receipt receipt) {
        if(!saved.id().equals(receipt.planId()) || !saved.runId().equals(receipt.runId()) || !saved.sha256().equals(receipt.planHash())) throw new ExperimentDrafts.Problem(503,"Activation receipt identity cannot be verified.");
    }
    @Service
    public static class Receipts {
        private final JdbcTemplate db;private final RunIntents runs;private final ExecutionPlans plans;private final WorkerPreparationDispatch.Receipts preparation;private final ObjectMapper json;
        public Receipts(JdbcTemplate db,RunIntents runs,ExecutionPlans plans,WorkerPreparationDispatch.Receipts preparation,ObjectMapper json) {this.db=db;this.runs=runs;this.plans=plans;this.preparation=preparation;this.json=json;}
        private Receipt read(java.sql.ResultSet row,int index) throws java.sql.SQLException {
            String patch=row.getString("patch_json"),hash=row.getString("patch_sha256");
            if(!K6PlanEnvelope.hash(patch).equals(hash)) throw new ExperimentDrafts.Problem(503,"Activation patch integrity cannot be verified.");
            return new Receipt(row.getObject("operation_id",UUID.class),row.getObject("plan_id",UUID.class),row.getObject("run_id",UUID.class),row.getString("plan_sha256"),row.getString("job_uid"),row.getString("resource_version"),patch,hash,row.getLong("run_revision"),row.getString("outcome"),row.getString("acknowledged_version"),row.getObject("requested_at",OffsetDateTime.class),row.getObject("acknowledged_at",OffsetDateTime.class));
        }
        @Transactional(readOnly=true) public Receipt get(UUID planId) {return db.query("SELECT * FROM experiment_worker_activation WHERE plan_id=?",this::read,planId).stream().findFirst().orElse(null);}
        @Transactional public Inputs inputs(UUID runId,String expectedHash) {
            if(db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR SHARE",UUID.class,runId).isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
            var saved=plans.get(runId);requireHash(saved,expectedHash);var run=runs.get(runId);
            if(run.status()!=RunLifecycle.Status.SUBMITTING || (run.workerState()!=null && !run.workerState().equals("PENDING"))) throw new ExperimentDrafts.Problem(409,"This run cannot activate a prepared worker.");
            if(run.operations().stream().noneMatch(op->op.id().equals(saved.submitOperationId()) && op.kind().equals("SUBMIT") && Set.of("PENDING","CONFIRMED").contains(op.status()))) throw new ExperimentDrafts.Problem(409,"A current submission intent is required before activation.");
            runs.verifyDispatchScope(runId);
            if(Boolean.TRUE.equals(db.queryForObject("SELECT deadline_at < clock_timestamp() + ? * INTERVAL '1 second' FROM experiment_run WHERE id=?",Boolean.class,run.maximumSeconds(),runId))) throw new ExperimentDrafts.Problem(409,"Run budget expired before activation.");
            String namespace=plans.clusterBinding(runId).namespace();var ids=new EnumMap<KubernetesWorkerPreparer.Resource,String>(KubernetesWorkerPreparer.Resource.class);
            for(var resource:KubernetesWorkerPreparer.Resource.values()) {
                var receipt=preparation.get(saved.id(),resource);
                if(receipt==null || !receipt.outcome().equals("ACKNOWLEDGED") || !receipt.planHash().equals(saved.sha256()) || !receipt.namespace().equals(namespace) || !receipt.workerName().equals(run.workerName()) || receipt.runRevision()!=saved.runRevision())
                    throw new ExperimentDrafts.Problem(409,"Every prepared resource requires matching acknowledged evidence before activation.");
                ids.put(resource,receipt.resourceUid());
            }
            String uid=ids.get(KubernetesWorkerPreparer.Resource.JOB);
            if(run.workerUid()!=null && !run.workerUid().equals(uid)) throw new ExperimentDrafts.Problem(409,"Prepared worker UID differs from the bound worker.");
            return new Inputs(saved,run,new KubernetesWorkerActivator.Resources(ids.get(KubernetesWorkerPreparer.Resource.FILES),ids.get(KubernetesWorkerPreparer.Resource.NETWORK),uid));
        }
        @Transactional public Claim claim(Inputs inspected,KubernetesWorkerActivator.Prepared command) {
            var runId=inspected.run().id();
            if(db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId).isEmpty()) throw new ExperimentDrafts.Problem(404,"Experiment run not found.");
            var saved=plans.get(runId);requireHash(saved,inspected.plan().sha256());var existing=get(saved.id());
            if(existing!=null) {verifyReceipt(saved,existing);return new Claim(existing,false);}
            var current=inputs(runId,saved.sha256());
            if(current.run().revision()!=inspected.run().revision()) throw new ExperimentDrafts.Problem(409,"Run changed during activation inspection.");
            KubernetesWorkerActivator.validateCommand(command,json);
            var job=plans.workerJob(current.run());
            try {
                if(!command.planHash().equals(saved.sha256()) || !command.jobUid().equals(current.resources().jobUid()) || !command.workerName().equals(current.run().workerName()) || !command.namespace().equals(job.getMetadata().getNamespace())
                        || !json.readTree(command.expectedJobJson()).equals(json.readTree(io.kubernetes.client.openapi.JSON.serialize(job)))) throw new IllegalArgumentException();
            } catch(Exception invalid) {throw new ExperimentDrafts.Problem(409,"Activation command differs from the saved worker.");}
            UUID operation=UUID.randomUUID();
            db.update("INSERT INTO experiment_operation(id,attempt_id,kind,status) VALUES (?,?,'ACTIVATE','PENDING')",operation,current.run().attemptId());
            db.update("INSERT INTO experiment_worker_activation(operation_id,plan_id,run_id,plan_sha256,job_uid,resource_version,patch_json,patch_sha256,run_revision,outcome) VALUES (?,?,?,?,?,?,?,?,?,'REQUESTED')",
                    operation,saved.id(),runId,saved.sha256(),command.jobUid(),command.resourceVersion(),command.patchJson(),command.patchHash(),current.run().revision()+1);
            db.update("UPDATE experiment_attempt SET worker_uid=?,worker_state='PENDING' WHERE id=?",command.jobUid(),current.run().attemptId());
            db.update("UPDATE experiment_operation SET status='CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE id=?",saved.submitOperationId());
            db.update("UPDATE experiment_run SET revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",runId);
            audit("WORKER_ACTIVATION_INTENT",operation);
            return new Claim(get(saved.id()),true);
        }
        @Transactional public Receipt finish(UUID operation,String outcome,String version) {
            if(!Set.of("ACKNOWLEDGED","CONFLICT","DENIED","UNCERTAIN").contains(outcome) || (outcome.equals("ACKNOWLEDGED")!=(version!=null))) throw new IllegalArgumentException("Invalid activation acknowledgement.");
            var rows=db.query("SELECT * FROM experiment_worker_activation WHERE operation_id=? FOR UPDATE",this::read,operation);
            if(rows.isEmpty()) throw new ExperimentDrafts.Problem(404,"Activation intent not found.");var receipt=rows.getFirst();
            if(version!=null && (version.isBlank() || version.length()>256 || version.equals(receipt.resourceVersion()))) throw new IllegalArgumentException("A new acknowledged worker revision is required.");
            if(!receipt.outcome().equals("REQUESTED")) {
                if(!receipt.outcome().equals(outcome) || !Objects.equals(receipt.acknowledgedVersion(),version)) throw new ExperimentDrafts.Problem(409,"Activation intent already has different evidence.");return receipt;
            }
            db.update("UPDATE experiment_worker_activation SET outcome=?,acknowledged_version=?,acknowledged_at=CURRENT_TIMESTAMP WHERE operation_id=?",outcome,version,operation);
            if(outcome.equals("ACKNOWLEDGED")) db.update("UPDATE experiment_operation SET status='CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE id=?",operation);
            audit("WORKER_ACTIVATION_ACK",operation);return get(receipt.planId());
        }
        private void audit(String action,UUID id) {db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES ('worker-activator',?,'EXPERIMENT',?,'SUCCESS')",action,id);}
    }
}
