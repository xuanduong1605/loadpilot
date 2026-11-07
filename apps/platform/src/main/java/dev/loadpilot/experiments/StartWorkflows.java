package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.*;
import dev.loadpilot.integrations.k6.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable start scheduling and admission receipt, separate from worker execution and SLO state. */
@Service
public class StartWorkflows {
    public record Claim(UUID runId,UUID token) {}
    public record View(UUID runId,String state,String reason,UUID planId,RuntimeAdmission.Grant admission,OffsetDateTime updatedAt) {}
    private final JdbcTemplate db;private final RunIntents runs;private final ExecutionPlans plans;private final ObjectMapper json;
    public StartWorkflows(JdbcTemplate db,RunIntents runs,ExecutionPlans plans,ObjectMapper json){this.db=db;this.runs=runs;this.plans=plans;this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);}
    @Transactional public RunIntents.Run reserve(RunIntents.Start input,String actor){var run=runs.reserve(input,actor);enqueue(run.id(),actor);return run;}
    @Transactional public void enqueue(UUID runId,String actor){
        var run=runs.get(runId);if(run.status().terminal())return;
        db.update("INSERT INTO experiment_start_workflow(run_id,actor) VALUES (?,?) ON CONFLICT DO NOTHING",runId,actor);
    }
    @Transactional public Claim claimNext(){
        var ids=db.queryForList("SELECT run_id FROM experiment_start_workflow WHERE state IN ('QUEUED','DISPATCHING') AND next_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY next_at,run_id LIMIT 1 FOR UPDATE SKIP LOCKED",UUID.class);
        return ids.isEmpty()?null:claim(ids.getFirst());
    }
    @Transactional public Claim claim(UUID id){
        UUID token=UUID.randomUUID();int changed=db.update("UPDATE experiment_start_workflow SET state='DISPATCHING',lease_token=?,lease_until=clock_timestamp()+INTERVAL '90 seconds',updated_at=clock_timestamp() WHERE run_id=? AND state IN ('QUEUED','DISPATCHING') AND (lease_until IS NULL OR lease_until<=clock_timestamp())",token,id);
        return changed==1?new Claim(id,token):null;
    }
    @Transactional(readOnly=true) public void requireOwner(Claim claim){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM experiment_start_workflow WHERE run_id=? AND lease_token=? AND lease_until>clock_timestamp() AND state='DISPATCHING')",Boolean.class,claim.runId(),claim.token())))throw new IllegalStateException("Start workflow ownership expired.");
    }
    @Transactional(readOnly=true) public String actor(UUID id){return db.queryForObject("SELECT actor FROM experiment_start_workflow WHERE run_id=?",String.class,id);}
    @Transactional(readOnly=true) public void requireActor(UUID id){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM experiment_start_workflow w JOIN users u ON u.username=w.actor JOIN authorities a ON a.username=u.username WHERE w.run_id=? AND u.enabled AND a.authority='ROLE_OPERATOR')",Boolean.class,id)))throw new ExperimentDrafts.Problem(403,"The requesting operator no longer has execution permission.");
    }
    @Transactional(readOnly=true) public View read(UUID id){
        runs.get(id);var rows=db.query("SELECT * FROM experiment_start_workflow WHERE run_id=?",(row,index)->{
            RuntimeAdmission.Grant grant=null;String text=row.getString("admission_json");
            if(text!=null)try{if(!K6PlanEnvelope.hash(text).equals(row.getString("admission_sha256")))throw new IllegalArgumentException();grant=json.readValue(text,RuntimeAdmission.Grant.class);var saved=plans.get(id);if(!saved.id().equals(row.getObject("plan_id",UUID.class)) || !saved.sha256().equals(grant.planSha256()))throw new IllegalArgumentException();}
            catch(Exception invalid){throw new ExperimentDrafts.Problem(503,"Stored start admission cannot be verified.");}
            return new View(id,row.getString("state"),row.getString("reason"),row.getObject("plan_id",UUID.class),grant,row.getObject("updated_at",OffsetDateTime.class));
        },id);return rows.isEmpty()?new View(id,"NOT_SCHEDULED","WAITING",null,null,null):rows.getFirst();
    }
    @Transactional public void authorize(Claim claim,ExecutionPlans.Saved plan,RuntimeAdmission.Grant grant){
        requireOwner(claim);var run=runs.get(claim.runId());
        if(grant==null || grant.verificationId()==null || !plan.runId().equals(run.id()) || !plan.sha256().equals(grant.planSha256()) || grant.evidenceSha256()==null || !grant.evidenceSha256().matches("[a-f0-9]{64}") || grant.validUntil()==null || grant.validUntil().isBefore(run.deadlineAt().toInstant()))throw new ExperimentDrafts.Problem(503,"Runtime admission does not cover this exact bounded worker.");
        var old=read(run.id());if(old.admission()!=null){if(!old.planId().equals(plan.id()) || !old.admission().equals(grant))throw new ExperimentDrafts.Problem(409,"Start already has different admission evidence.");return;}
        try{String text=json.writeValueAsString(grant);if(db.update("UPDATE experiment_start_workflow SET plan_id=?,admission_json=?,admission_sha256=?,updated_at=clock_timestamp() WHERE run_id=? AND lease_token=? AND plan_id IS NULL",plan.id(),text,K6PlanEnvelope.hash(text),run.id(),claim.token())!=1)throw new IllegalStateException();}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Cannot persist runtime admission.");}
        db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES (?,'RUN_ADMISSION_RECORDED','EXPERIMENT',?,'SUCCESS')",actor(run.id()),run.id());
    }
    @Transactional public void finish(Claim claim,String state,String reason){
        if(!Set.of("OBSERVING","HALTED","FAILED","RECOVERY_REQUIRED").contains(state) || !Set.of("OBSERVING","STOP_REQUESTED","START_REJECTED","RECONCILIATION_REQUIRED").contains(reason))throw new IllegalArgumentException();
        db.update("UPDATE experiment_start_workflow SET state=?,reason=?,lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE run_id=? AND lease_token=?",state,reason,claim.runId(),claim.token());
    }
    /** Run lock and commit-before-effect receipts prove whether any external creation was possible. */
    @Transactional public boolean rejectWithoutEffects(UUID id){
        db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,id);var run=runs.get(id);
        if(run.status().terminal())return true;
        if(run.workerUid()!=null || db.queryForObject("SELECT count(*) FROM experiment_worker_preparation w JOIN experiment_execution_plan p ON p.id=w.plan_id WHERE p.run_id=?",Integer.class,id)!=0 || db.queryForObject("SELECT count(*) FROM experiment_worker_activation WHERE run_id=?",Integer.class,id)!=0)return false;
        String reason=db.queryForObject("SELECT stop_reason FROM experiment_run WHERE id=?",String.class,id);
        String next=run.status()==RunLifecycle.Status.STOPPING && "USER".equals(reason)?"CANCELLED":"FAILED";
        db.update("UPDATE experiment_run SET status=?,revision=revision+1,updated_at=clock_timestamp() WHERE id=?",next,id);
        db.update("UPDATE experiment_operation SET status=CASE WHEN kind='STOP' THEN 'CONFIRMED' ELSE 'CANCELLED' END,updated_at=clock_timestamp() WHERE attempt_id=? AND status='PENDING'",run.attemptId());
        db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES ('start-coordinator','RUN_START_NO_EFFECTS_REJECTED','EXPERIMENT',?,'SUCCESS')",id);return true;
    }
}
