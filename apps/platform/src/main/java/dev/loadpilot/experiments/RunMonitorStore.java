package dev.loadpilot.experiments;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable polling queue. Leases coordinate reads; they never authorize submission or activation. */
@Service
public class RunMonitorStore {
    public enum Outcome { WAITING, UNAVAILABLE, RESULT_SAVED, NO_WORKER, CLEANED_NO_RESULT }
    public record Claim(UUID runId, UUID token) {}
    public record View(UUID runId, String stopReason, String outcome, long attempts, boolean finished,
                       boolean processing, OffsetDateTime checkedAt, OffsetDateTime nextAt) {}
    private final JdbcTemplate db;
    private final RunIntents runs;
    public RunMonitorStore(JdbcTemplate db,RunIntents runs) {this.db=db;this.runs=runs;}
    private static final String DISCOVER="""
        INSERT INTO experiment_run_monitor(run_id)
        SELECT r.id FROM experiment_run r JOIN experiment_attempt a ON a.run_id=r.id AND a.attempt_number=1
        LEFT JOIN experiment_run_monitor m ON m.run_id=r.id
        WHERE (m.run_id IS NULL AND (r.status IN ('QUEUED','SUBMITTING','RUNNING','STOPPING','RECOVERY_REQUIRED')
          OR (a.worker_uid IS NOT NULL AND EXISTS (SELECT 1 FROM experiment_execution_plan p WHERE p.run_id=r.id)
              AND NOT EXISTS (SELECT 1 FROM experiment_worker_result x WHERE x.run_id=r.id))))
          OR ((m.run_id IS NULL OR m.finished) AND %s)
        ORDER BY r.created_at,r.id LIMIT 32
        ON CONFLICT (run_id) DO UPDATE SET finished=FALSE,outcome='WAITING',next_at=clock_timestamp()
        WHERE experiment_run_monitor.finished
        """.formatted(WorkerRecoveryStore.UNVERIFIED_RESOURCES);
    @Transactional public Claim claimNext() {
        db.update(DISCOVER);
        var ids=db.queryForList("SELECT run_id FROM experiment_run_monitor WHERE NOT finished AND next_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY next_at,run_id LIMIT 1 FOR UPDATE SKIP LOCKED",UUID.class);
        return ids.isEmpty()?null:claim(ids.getFirst());
    }
    @Transactional public void wake(UUID id){db.update("INSERT INTO experiment_run_monitor(run_id) VALUES (?) ON CONFLICT (run_id) DO UPDATE SET next_at=LEAST(experiment_run_monitor.next_at,clock_timestamp()) WHERE NOT experiment_run_monitor.finished",id);}
    @Transactional(readOnly=true) public boolean startPreparing(UUID id){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM experiment_start_workflow WHERE run_id=? AND state IN ('QUEUED','DISPATCHING'))",Boolean.class,id));}
    @Transactional public Claim claim(UUID runId) {
        runs.get(runId);
        db.update("INSERT INTO experiment_run_monitor(run_id) VALUES (?) ON CONFLICT DO NOTHING",runId);
        UUID token=UUID.randomUUID();
        int changed=db.update("UPDATE experiment_run_monitor SET lease_token=?,lease_until=clock_timestamp()+INTERVAL '90 seconds',attempts=attempts+1 WHERE run_id=? AND NOT finished AND next_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp())",token,runId);
        return changed==1?new Claim(runId,token):null;
    }
    @Transactional public boolean finish(Claim claim,Outcome outcome) {
        if(claim==null || outcome==null) throw new IllegalArgumentException("A monitor claim and outcome are required.");
        // Expired workers cannot overwrite a newer queue receipt. External stop/result effects have their own durable guards.
        return db.update("""
            UPDATE experiment_run_monitor SET outcome=?,finished=?,checked_at=clock_timestamp(),
              failures=CASE WHEN ? THEN LEAST(failures+1,6) ELSE 0 END,
              next_at=clock_timestamp() + (CASE WHEN ? THEN LEAST(300,15*power(2,failures)) ELSE 2 END)*INTERVAL '1 second',
              lease_token=NULL,lease_until=NULL
            WHERE run_id=? AND lease_token=?
            """,outcome.name(),outcome==Outcome.RESULT_SAVED || outcome==Outcome.NO_WORKER || outcome==Outcome.CLEANED_NO_RESULT,
                outcome==Outcome.UNAVAILABLE,outcome==Outcome.UNAVAILABLE,claim.runId(),claim.token())==1;
    }
    @Transactional(readOnly=true) public View read(UUID runId) {
        runs.get(runId);
        return db.query("""
            SELECT r.stop_reason,m.*,COALESCE(m.lease_until>clock_timestamp(),FALSE) processing
            FROM experiment_run r LEFT JOIN experiment_run_monitor m ON m.run_id=r.id WHERE r.id=?
            """,(row,index)->new View(runId,row.getString("stop_reason"),row.getString("outcome")==null?"NOT_SCHEDULED":row.getString("outcome"),
                row.getLong("attempts"),row.getBoolean("finished"),row.getBoolean("processing"),row.getObject("checked_at",OffsetDateTime.class),row.getObject("next_at",OffsetDateTime.class)),runId).getFirst();
    }
}
