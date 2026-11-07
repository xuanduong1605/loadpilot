package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recovery observations are separate from original (possibly uncertain) creation acknowledgements. */
@Service
public class WorkerRecoveryStore {
    public record Row(UUID preparationId,KubernetesWorkerPreparer.Resource resource,String creationOutcome,String knownUid,UUID deleteOperation,String deleteVersion,String deleteOutcome,OffsetDateTime absentAt){}
    public record Claim(Row row,boolean dispatch){}
    private final JdbcTemplate db;private final RunIntents runs;
    public WorkerRecoveryStore(JdbcTemplate db,RunIntents runs){this.db=db;this.runs=runs;}
    @Transactional public List<Row> rows(UUID runId){
        runs.get(runId);
        db.update("INSERT INTO experiment_resource_recovery(preparation_id,run_id,known_uid) SELECT w.id,p.run_id,w.resource_uid FROM experiment_worker_preparation w JOIN experiment_execution_plan p ON p.id=w.plan_id WHERE p.run_id=? ON CONFLICT DO NOTHING",runId);
        return db.query("SELECT r.*,w.resource,w.outcome FROM experiment_resource_recovery r JOIN experiment_worker_preparation w ON w.id=r.preparation_id WHERE r.run_id=? ORDER BY w.resource",(row,index)->new Row(row.getObject("preparation_id",UUID.class),KubernetesWorkerPreparer.Resource.valueOf(row.getString("resource")),row.getString("outcome"),row.getString("known_uid"),row.getObject("delete_operation_id",UUID.class),row.getString("delete_version"),row.getString("delete_outcome"),row.getObject("absence_verified_at",OffsetDateTime.class)),runId);
    }
    @Transactional public void bind(UUID runId,UUID preparation,KubernetesWorkerRecovery.Found found){
        db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId);var row=rows(runId).stream().filter(r->r.preparationId().equals(preparation)).findFirst().orElseThrow();
        if(row.resource()!=found.resource() || (row.knownUid()!=null && !row.knownUid().equals(found.uid())))throw new ExperimentDrafts.Problem(409,"Recovered resource identity changed.");
        if(db.update("UPDATE experiment_resource_recovery SET known_uid=?,observed_sha256=?,updated_at=clock_timestamp() WHERE preparation_id=? AND known_uid IS NULL",found.uid(),found.sha256(),preparation)==1)audit("WORKER_RESOURCE_IDENTIFIED",runId);
    }
    @Transactional public Claim claimDelete(UUID runId,UUID preparation,KubernetesWorkerRecovery.Found found){
        db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId);var run=runs.get(runId);
        if(!run.status().terminal() && !beforeJob(runId))throw new ExperimentDrafts.Problem(409,"Worker termination is required before resource cleanup.");
        bind(runId,preparation,found);var row=rows(runId).stream().filter(r->r.preparationId().equals(preparation)).findFirst().orElseThrow();
        if(row.absentAt()!=null || row.deleteOperation()!=null)return new Claim(row,false);
        UUID operation=UUID.randomUUID();db.update("UPDATE experiment_resource_recovery SET delete_operation_id=?,delete_version=?,delete_outcome='REQUESTED',updated_at=clock_timestamp() WHERE preparation_id=?",operation,found.resourceVersion(),preparation);audit("WORKER_CLEANUP_INTENT",runId);
        return new Claim(rows(runId).stream().filter(r->r.preparationId().equals(preparation)).findFirst().orElseThrow(),true);
    }
    @Transactional public void finishDelete(UUID preparation,String outcome){
        if(!Set.of("ACKNOWLEDGED","UNCERTAIN","DENIED","CONFLICT").contains(outcome))throw new IllegalArgumentException();
        db.update("UPDATE experiment_resource_recovery SET delete_outcome=?,updated_at=clock_timestamp() WHERE preparation_id=? AND delete_outcome='REQUESTED'",outcome,preparation);
    }
    @Transactional public void absent(UUID runId,UUID preparation,String uid){
        if(uid==null)throw new IllegalArgumentException("An uncertain missing create is not absence proof.");
        if(db.update("UPDATE experiment_resource_recovery SET absence_verified_at=clock_timestamp(),updated_at=clock_timestamp() WHERE preparation_id=? AND run_id=? AND known_uid=? AND absence_verified_at IS NULL",preparation,runId,uid)==1)audit("WORKER_RESOURCE_REMOVED",runId);
    }
    @Transactional(readOnly=true) public boolean beforeJob(UUID id){
        var run=runs.get(id);
        return run.status()==RunLifecycle.Status.STOPPING && run.workerUid()==null && db.queryForObject("SELECT count(*) FROM experiment_worker_preparation w JOIN experiment_execution_plan p ON p.id=w.plan_id WHERE p.run_id=? AND w.resource='JOB'",Integer.class,id)==0 && db.queryForObject("SELECT count(*) FROM experiment_worker_activation WHERE run_id=?",Integer.class,id)==0;
    }
    @Transactional public void completeBeforeJob(UUID id){
        db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,id);
        if(!beforeJob(id) || rows(id).stream().anyMatch(r->r.absentAt()==null))throw new ExperimentDrafts.Problem(409,"Unresolved resource creation retains recovery protection.");
        String reason=db.queryForObject("SELECT stop_reason FROM experiment_run WHERE id=?",String.class,id);
        db.update("UPDATE experiment_run SET status=?,revision=revision+1,updated_at=clock_timestamp() WHERE id=?","USER".equals(reason)?"CANCELLED":"FAILED",id);
        db.update("UPDATE experiment_operation SET status=CASE WHEN kind='STOP' THEN 'CONFIRMED' ELSE 'CANCELLED' END,updated_at=clock_timestamp() WHERE attempt_id=? AND status='PENDING'",runs.get(id).attemptId());audit("WORKER_RECOVERY_COMPLETED",id);
    }
    @Transactional public void completeWorkflow(UUID id){
        if(!runs.get(id).status().terminal() || rows(id).stream().anyMatch(r->r.absentAt()==null))return;
        db.update("UPDATE experiment_start_workflow SET state=CASE WHEN ?='FAILED' THEN 'FAILED' ELSE 'HALTED' END,reason='STOP_REQUESTED',lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE run_id=? AND state IN ('RECOVERY_REQUIRED','DISPATCHING','OBSERVING')",runs.get(id).status().name(),id);
    }
    private void audit(String action,UUID run){db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES ('resource-recovery',?,'EXPERIMENT',?,'SUCCESS')",action,run);}
}
