package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.experiments.RunDispatch;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import java.time.*;
import java.util.*;

/** Reads one exact, previously proven Pod's current k6 log between repeated identity checks. */
public final class KubernetesWorkerLogs implements AutoCloseable {
    public record PodIdentity(String namespace,String name,String uid,String jobUid,Instant startedAt) {}
    private final ApiClient client;private final CoreV1Api pods;private final K6LiveHttpMetrics metrics;
    public KubernetesWorkerLogs(ApiClient ownedClient,ObjectMapper json) {client=KubernetesWorkerTransport.bound(ownedClient);pods=new CoreV1Api(client);metrics=new K6LiveHttpMetrics(json);}
    static PodIdentity identity(RunDispatch.Ticket ticket,V1Job expected,String jobUid,V1Pod pod) {
        if(pod==null || pod.getMetadata()==null || pod.getSpec()==null || pod.getSpec().getContainers()==null || pod.getStatus()==null || !"Running".equals(pod.getStatus().getPhase()) || pod.getStatus().getContainerStatuses()==null) throw new IllegalArgumentException();
        var meta=pod.getMetadata();var trace=Map.of("loadpilot.dev/run",ticket.runId().toString(),"loadpilot.dev/attempt",ticket.attemptId().toString(),"loadpilot.dev/shard",ticket.shardId().toString());
        if(meta.getName()==null || !meta.getName().startsWith(ticket.workerName()+"-") || !Objects.equals(expected.getMetadata().getNamespace(),meta.getNamespace()) || meta.getUid()==null || meta.getUid().isBlank()
                || meta.getLabels()==null || !meta.getLabels().entrySet().containsAll(trace.entrySet()) || meta.getOwnerReferences()==null
                || meta.getOwnerReferences().stream().filter(owner->Boolean.TRUE.equals(owner.getController())).count()!=1
                || meta.getOwnerReferences().stream().noneMatch(owner->Boolean.TRUE.equals(owner.getController()) && "Job".equals(owner.getKind()) && ticket.workerName().equals(owner.getName()) && jobUid.equals(owner.getUid()))
                || pod.getSpec().getContainers().stream().noneMatch(container->"k6".equals(container.getName()))) throw new IllegalArgumentException();
        var states=pod.getStatus().getContainerStatuses().stream().filter(status->"k6".equals(status.getName())).toList();
        if(states.size()!=1) throw new IllegalArgumentException();var state=states.getFirst();
        if(state.getRestartCount()==null || state.getRestartCount()!=0 || state.getState()==null || state.getState().getRunning()==null || state.getState().getRunning().getStartedAt()==null
                || state.getState().getTerminated()!=null || state.getState().getWaiting()!=null) throw new IllegalArgumentException();
        return new PodIdentity(meta.getNamespace(),meta.getName(),meta.getUid(),jobUid,state.getState().getRunning().getStartedAt().toInstant());
    }
    public K6LiveHttpMetrics.Snapshot collect(RunDispatch.Ticket ticket,V1Job expected,PodIdentity scope,Instant now) {
        try {
            var first=pods.readNamespacedPod(scope.name(),scope.namespace()).execute();
            if(!scope.equals(identity(ticket,expected,scope.jobUid(),first))) throw new IllegalArgumentException();
            Instant until=now.minusSeconds(2),from=until.minusSeconds(10);
            if(scope.startedAt().isAfter(from)) from=scope.startedAt();
            if(!from.isBefore(until)) return K6LiveHttpMetrics.unavailable("WAITING","Waiting for the worker live window to begin.",now);
            String text=pods.readNamespacedPodLog(scope.name(),scope.namespace()).container("k6").follow(false).previous(false).timestamps(true).sinceSeconds(20).limitBytes(262144).execute();
            var last=pods.readNamespacedPod(scope.name(),scope.namespace()).execute();
            if(!scope.equals(identity(ticket,expected,scope.jobUid(),last))) throw new IllegalArgumentException();
            return metrics.parse(text,from,until,now,scope.uid()).correlated(ticket,scope.jobUid());
        } catch(Exception unavailable) {return K6LiveHttpMetrics.unavailable("UNAVAILABLE","Worker metrics could not be collected with a stable Pod identity.",now);}
    }
    @Override public void close() {client.getHttpClient().connectionPool().evictAll();client.getHttpClient().dispatcher().executorService().shutdown();}
}
