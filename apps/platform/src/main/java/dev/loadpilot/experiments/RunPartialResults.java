package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.K6LiveHttpMetrics;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable sampled windows from the running worker. Each window has its own scope and provenance. */
@Service
public class RunPartialResults {
    public record Window(K6LiveHttpMetrics.Identity identity,UUID planId,String planSha256,String podUid,
                         Instant windowFrom,Instant windowUntil,Instant collectedAt,int requestCount,int errorCount,
                         double requestsPerSecond,double p95LatencyMs,double errorRatio,String source,String algorithm,String coverage) {}

    private final JdbcTemplate db;
    private final RunIntents runs;
    private final ExecutionPlans plans;

    public RunPartialResults(JdbcTemplate db,RunIntents runs,ExecutionPlans plans) {
        this.db=db;
        this.runs=runs;
        this.plans=plans;
    }

    /** Reserve one read before I/O. Failed attempts respect the same polling interval as successful reads. */
    @Transactional
    public UUID claimNext() {
        return db.queryForList("""
            UPDATE experiment_attempt SET next_http_capture_at=clock_timestamp()+INTERVAL '5 seconds'
            WHERE id=(
                SELECT a.id FROM experiment_attempt a JOIN experiment_run r ON r.id=a.run_id
                JOIN experiment_execution_plan p ON p.attempt_id=a.id AND p.run_id=r.id
                JOIN experiment_operation o ON o.id=p.submit_operation_id
                WHERE a.attempt_number=1 AND r.status='RUNNING' AND a.worker_state='RUNNING'
                  AND a.worker_uid IS NOT NULL AND o.kind='SUBMIT' AND o.status='CONFIRMED' AND r.deadline_at>clock_timestamp()
                  AND (a.next_http_capture_at IS NULL OR a.next_http_capture_at<=clock_timestamp())
                ORDER BY a.next_http_capture_at NULLS FIRST,a.id
                LIMIT 1 FOR UPDATE OF a SKIP LOCKED
            ) RETURNING run_id
            """,UUID.class).stream().findFirst().orElse(null);
    }

    @Transactional
    public boolean retain(UUID runId,K6LiveHttpMetrics.Snapshot sample) {
        if(!valid(sample) || db.queryForList("SELECT id FROM experiment_run WHERE id=? FOR UPDATE",UUID.class,runId).isEmpty())return false;
        var run=runs.get(runId);
        if(!sampling(run) || !identity(run).equals(sample.identity()) || sample.collectedAt().isAfter(run.deadlineAt().toInstant()))return false;
        var plan=plans.get(runId);
        if(run.operations().stream().noneMatch(op->op.id().equals(plan.submitOperationId()) && op.kind().equals("SUBMIT") && op.status().equals("CONFIRMED")))return false;
        return db.update("""
            INSERT INTO experiment_worker_http_window
                (attempt_id,plan_id,job_uid,pod_uid,window_from,window_until,collected_at,
                 request_count,error_count,requests_per_second,p95_latency_ms,error_ratio,source,algorithm)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (attempt_id,window_until) DO NOTHING
            """,run.attemptId(),plan.id(),run.workerUid(),sample.podUid(),utc(sample.windowFrom()),utc(sample.windowUntil()),utc(sample.collectedAt()),
            sample.requestCount().intValue(),sample.errorCount().intValue(),sample.requestsPerSecond(),sample.p95LatencyMs(),sample.errorRatio(),sample.source(),sample.algorithm())==1;
    }

    @Transactional(readOnly=true)
    public Window latest(RunIntents.Run run) {
        return db.query("""
            SELECT w.*,p.sha256 FROM experiment_worker_http_window w JOIN experiment_execution_plan p ON p.id=w.plan_id
            WHERE w.attempt_id=? AND p.run_id=? AND w.job_uid=? ORDER BY window_until DESC LIMIT 1
            """,(row,index)->new Window(identity(run),row.getObject("plan_id",UUID.class),row.getString("sha256"),row.getString("pod_uid"),
            row.getObject("window_from",OffsetDateTime.class).toInstant(),row.getObject("window_until",OffsetDateTime.class).toInstant(),
            row.getObject("collected_at",OffsetDateTime.class).toInstant(),row.getInt("request_count"),row.getInt("error_count"),
            row.getDouble("requests_per_second"),row.getDouble("p95_latency_ms"),row.getDouble("error_ratio"),row.getString("source"),
            row.getString("algorithm"),"PARTIAL_HTTP_WINDOW_NOT_SLO"),run.attemptId(),run.id(),run.workerUid()).stream().findFirst().orElse(null);
    }

    static boolean sampling(RunIntents.Run run) {
        return run.status()==RunLifecycle.Status.RUNNING && "RUNNING".equals(run.workerState()) && run.workerUid()!=null;
    }

    private static K6LiveHttpMetrics.Identity identity(RunIntents.Run run) {
        return new K6LiveHttpMetrics.Identity(run.id(),run.attemptId(),run.shardId(),run.workerUid());
    }

    private static boolean valid(K6LiveHttpMetrics.Snapshot sample) {
        return sample!=null && "READY".equals(sample.state()) && "BEST_EFFORT_LIVE_NOT_SLO".equals(sample.coverage())
                && sample.podUid()!=null && sample.podUid().matches("[A-Za-z0-9._:-]{1,128}")
                && sample.windowFrom()!=null && sample.windowUntil()!=null && sample.collectedAt()!=null
                && sample.windowFrom().isBefore(sample.windowUntil()) && !sample.windowUntil().isAfter(sample.collectedAt())
                && Duration.between(sample.windowFrom(),sample.windowUntil()).compareTo(Duration.ofSeconds(10))<=0
                && sample.requestCount()!=null && sample.requestCount()>0 && sample.requestCount()<=10000
                && sample.errorCount()!=null && sample.errorCount()>=0 && sample.errorCount()<=sample.requestCount()
                && sample.requestsPerSecond()!=null && Double.isFinite(sample.requestsPerSecond()) && sample.requestsPerSecond()>0
                && sample.p95LatencyMs()!=null && Double.isFinite(sample.p95LatencyMs()) && sample.p95LatencyMs()>=0
                && sample.errorRatio()!=null && sample.errorRatio()==(double)sample.errorCount()/sample.requestCount()
                && "k6 JSON / Kubernetes Pod log".equals(sample.source()) && "live-http-json-v1/type7".equals(sample.algorithm());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
