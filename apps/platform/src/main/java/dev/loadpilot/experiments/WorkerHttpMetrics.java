package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Read-only live telemetry; no run transitions, script execution or raw log persistence. */
@Service
public class WorkerHttpMetrics {
    private final RunIntents runs;private final ExecutionPlans plans;private final ExecutionClusterClients clients;private final ObjectMapper json;
    private final Semaphore capacity=new Semaphore(2);
    public WorkerHttpMetrics(RunIntents runs,ExecutionPlans plans,ExecutionClusterClients clients,ObjectMapper json) {this.runs=runs;this.plans=plans;this.clients=clients;this.json=json;}
    @Transactional(propagation=Propagation.NEVER)
    public K6LiveHttpMetrics.Snapshot read(RunIntents.Run run) {
        Instant now=Instant.now();
        if(!active(run) || run.workerUid()==null) return K6LiveHttpMetrics.unavailable("UNAVAILABLE","Waiting for a bound running worker with HTTP telemetry.",now);
        if(!capacity.tryAcquire()) return K6LiveHttpMetrics.unavailable("BUSY","Live worker metrics are busy. Retry after the polling interval.",now);
        try {
            var job=plans.workerJob(run);var container=job.getSpec().getTemplate().getSpec().getContainers().getFirst();
            if(container.getArgs()==null || !container.getArgs().contains("json=-") || !container.getArgs().contains("--log-output=none"))
                return K6LiveHttpMetrics.unavailable("NOT_CONFIGURED","This saved worker plan has no controlled live HTTP output. Review new inputs before a later run.",now);
            var binding=plans.clusterBinding(run.id());
            if(!binding.operations().containsAll(List.of("READ","METRICS"))) return K6LiveHttpMetrics.unavailable("NOT_CONFIGURED","Live HTTP collection requires explicit worker read and metrics grants.",now);
            var submit=run.operations().stream().filter(op->op.kind().equals("SUBMIT") && op.status().equals("CONFIRMED")).findFirst().orElseThrow();
            var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),run.revision(),run.attemptId(),run.shardId(),submit.id(),run.workerName(),run.maximumSeconds(),run.deadlineAt());
            var readScope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),run.workerUid(),ExecutionClusterClients.Access.READ);
            KubernetesWorkerLogs.PodIdentity pod;
            try(var session=clients.openBound(readScope,binding);var collector=new KubernetesWorkerCollector(session.client())) {pod=collector.metricsPod(ticket,job,run.workerUid());}
            var metricScope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),run.workerUid(),ExecutionClusterClients.Access.METRICS);
            K6LiveHttpMetrics.Snapshot result;
            try(var session=clients.openWorkerLogs(metricScope,binding,pod);var logs=new KubernetesWorkerLogs(session.client(),json)) {result=logs.collect(ticket,job,pod,Instant.now());}
            var current=runs.get(run.id());
            if(current.revision()!=run.revision() || !Objects.equals(current.workerUid(),run.workerUid()) || !active(current))
                return K6LiveHttpMetrics.unavailable("STATE_CHANGED","Run changed during live metric collection. Poll again.",now);
            return result;
        } catch(RuntimeException unavailable) {return K6LiveHttpMetrics.unavailable("UNAVAILABLE","Worker HTTP metrics are unavailable; execution status and SLO verdict are unchanged.",now);}
        finally {capacity.release();}
    }
    private boolean active(RunIntents.Run run) {return run.status()==RunLifecycle.Status.RUNNING || run.status()==RunLifecycle.Status.STOPPING || run.status()==RunLifecycle.Status.RECOVERY_REQUIRED;}
}
