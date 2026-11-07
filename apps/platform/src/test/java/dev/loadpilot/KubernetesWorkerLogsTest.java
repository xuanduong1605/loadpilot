package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.*;
import dev.loadpilot.integrations.k6.*;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.tls.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesWorkerLogsTest {
    final ObjectMapper json=new ObjectMapper();final Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    final UUID attempt=UUID.randomUUID();
    final RunDispatch.Ticket ticket=new RunDispatch.Ticket(UUID.randomUUID(),UUID.randomUUID(),2,attempt,UUID.randomUUID(),UUID.randomUUID(),"lp-"+attempt,25,OffsetDateTime.now().plusMinutes(1));
    V1Job expected() {return new V1Job().metadata(new V1ObjectMeta().name(ticket.workerName()).namespace("runners").uid("job-uid").resourceVersion("1")
            .labels(Map.of("loadpilot.dev/run",ticket.runId().toString(),"loadpilot.dev/attempt",ticket.attemptId().toString(),"loadpilot.dev/shard",ticket.shardId().toString()))
            .annotations(Map.of("loadpilot.dev/snapshot",ticket.snapshotId().toString(),"loadpilot.dev/submit-operation",ticket.submitOperationId().toString()))).status(new V1JobStatus().active(1));}
    V1Pod pod(V1Job job) {return new V1Pod().apiVersion("v1").kind("Pod").metadata(new V1ObjectMeta().name(job.getMetadata().getName()+"-pod").namespace(job.getMetadata().getNamespace()).uid("pod-uid").resourceVersion("1").labels(job.getMetadata().getLabels())
            .ownerReferences(List.of(new V1OwnerReference().apiVersion("batch/v1").kind("Job").name(job.getMetadata().getName()).uid(job.getMetadata().getUid()).controller(true))))
            .spec(new V1PodSpec().containers(List.of(new V1Container().name("k6").image("fixture.invalid/k6"))))
            .status(new V1PodStatus().phase("Running").containerStatuses(List.of(new V1ContainerStatus().name("k6").image("fixture.invalid/k6").imageID("fixture").ready(true).restartCount(0)
                    .state(new V1ContainerState().running(new V1ContainerStateRunning().startedAt(now.minusSeconds(30).atOffset(ZoneOffset.UTC)))))));}
    class Fixture implements AutoCloseable {
        final HttpsServer server;final ExecutorService executor=Executors.newFixedThreadPool(2);final String ca;
        final List<String> requests=new CopyOnWriteArrayList<>();volatile V1Job job=expected();volatile List<V1Pod> selected=List.of(pod(job));
        volatile boolean replacePod,denyLogs;int podReads;String text;
        Fixture() throws Exception {
            var samples=new K6LiveHttpMetricsTest();text=samples.request(now.minusSeconds(6),100,"200","0")+samples.request(now.minusSeconds(5),300,"500","0");
            // Kubernetes adds its own current timestamps; the sample generator's fixed clock is intentionally replaced.
            text=text.replace(samples.now.minusSeconds(1).toString(),now.minusSeconds(1).toString());
            var certificate=new HeldCertificate.Builder().commonName("metric-fixture").addSubjectAlternativeName("127.0.0.1").certificateAuthority(0).build();ca=certificate.certificatePem();
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslContext()));server.setExecutor(executor);
            server.createContext("/",exchange->{
                requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());String path=exchange.getRequestURI().getPath(),body;int status=200;boolean log=path.endsWith("/log");
                if(!"GET".equals(exchange.getRequestMethod()) || !"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {status=403;body="private upstream detail";}
                else if(log) {status=denyLogs?403:200;body=denyLogs?"private upstream detail":text;}
                else if(path.contains("/jobs/")) body=JSON.serialize(job);
                else if(path.endsWith("/pods")) body=JSON.serialize(new V1PodList().metadata(new V1ListMeta().resourceVersion("1")).items(selected));
                else {
                    var value=JSON.<V1Pod>deserialize(JSON.serialize(selected.getFirst()),V1Pod.class);
                    if(replacePod && ++podReads>1) value.getMetadata().uid("replacement-pod");body=JSON.serialize(value);
                }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",log?"text/plain":"application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
        }
        String origin(){return "https://127.0.0.1:"+server.getAddress().getPort();}
        ApiClient client(){var client=new ApiClient().setBasePath(origin()).setSslCaCert(new ByteArrayInputStream(ca.getBytes(StandardCharsets.UTF_8)));client.addDefaultHeader("Authorization","Bearer fixture-token");return client;}
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
    KubernetesWorkerLogs.PodIdentity identity() {return new KubernetesWorkerLogs.PodIdentity("runners",ticket.workerName()+"-pod","pod-uid","job-uid",now.minusSeconds(30));}
    @Test void selectsOwnedRunningPodAndReadsOneBoundedRecentWindowWithoutDoubleCounting() throws Exception {
        try(var server=new Fixture();var collector=new KubernetesWorkerCollector(server.client())) {
            var pod=collector.metricsPod(ticket,expected(),"job-uid");assertThat(pod).isEqualTo(identity());
            try(var logs=new KubernetesWorkerLogs(server.client(),json)) {
                var first=logs.collect(ticket,expected(),pod,now);assertThat(first.state()).isEqualTo("READY");assertThat(first.requestCount()).isEqualTo(2);assertThat(first.requestsPerSecond()).isEqualTo(.2);assertThat(first.errorRatio()).isEqualTo(.5);assertThat(first.p95LatencyMs()).isEqualTo(290);
                assertThat(logs.collect(ticket,expected(),pod,now)).isEqualTo(first);
                assertThat(first.identity()).isEqualTo(new K6LiveHttpMetrics.Identity(ticket.runId(),ticket.attemptId(),ticket.shardId(),"job-uid"));
                var other=JSON.<V1Pod>deserialize(JSON.serialize(server.selected.getFirst()),V1Pod.class);other.getMetadata().uid("other-pod").name(ticket.workerName()+"-other");server.selected=List.of(server.selected.getFirst(),other);
                assertThatThrownBy(()->collector.metricsPod(ticket,expected(),"job-uid")).isInstanceOf(KubernetesWorkerCollector.Problem.class);
            }
            assertThat(server.requests.stream().filter(request->request.contains("/log?")).count()).isEqualTo(2);
            assertThat(server.requests).allMatch(request->request.startsWith("GET "));
        }
    }
    @Test void changedPodAndDeniedLogsCannotReturnMetricsOrEchoRawBody() throws Exception {
        for(boolean replaced:List.of(false,true)) try(var server=new Fixture();var logs=new KubernetesWorkerLogs(server.client(),json)) {
            server.replacePod=replaced;server.denyLogs=!replaced;
            var result=logs.collect(ticket,expected(),identity(),now);assertThat(result.state()).isEqualTo("UNAVAILABLE");assertThat(result.requestCount()).isNull();assertThat(result.toString()).doesNotContain("private upstream");
        }
    }
    @Test void explicitMetricGrantLimitsRequestsToOnePodAndFixedLogQuery() throws Exception {
        try(var server=new Fixture()) {
            var id=UUID.randomUUID();var store=mock(ConnectionStore.class);when(store.view(id)).thenReturn(new ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));when(store.kubernetesMaterial(id)).thenReturn(new ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token"));
            var config=json.writeValueAsString(List.of(Map.of("targetId",id.toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace","runners","operations",List.of("METRICS"))));
            var provider=new ExecutionClusterClients(config,json,store);var binding=provider.binding(id,"runners");var scope=new ExecutionClusterClients.Scope(id,1,"runners",ticket.workerName(),"job-uid",ExecutionClusterClients.Access.METRICS);
            assertThatThrownBy(()->provider.open(scope)).isInstanceOf(ExecutionClusterClients.Problem.class);verify(store,never()).kubernetesMaterial(id);
            try(var session=provider.openWorkerLogs(scope,binding,identity());var logs=new KubernetesWorkerLogs(session.client(),json)) {
                var api=new CoreV1Api(session.client());
                assertThatThrownBy(()->api.readNamespacedPod("other-pod","runners").execute()).isInstanceOf(ApiException.class);
                assertThatThrownBy(()->api.readNamespacedPodLog(identity().name(),"runners").container("k6").follow(true).previous(false).timestamps(true).sinceSeconds(20).limitBytes(262144).execute()).isInstanceOf(ApiException.class);
                assertThat(server.requests).isEmpty();assertThat(logs.collect(ticket,expected(),identity(),now).state()).isEqualTo("READY");assertThat(server.requests).hasSize(3);
            }
        }
    }
}
