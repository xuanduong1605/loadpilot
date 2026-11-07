package dev.loadpilot;

import com.sun.net.httpserver.*;
import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.k6.KubernetesWorkerCollector;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.models.*;
import okhttp3.tls.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KubernetesWorkerCollectorTest {
    final UUID attempt=UUID.randomUUID();
    final RunDispatch.Ticket ticket=new RunDispatch.Ticket(UUID.randomUUID(),UUID.randomUUID(),2,attempt,UUID.randomUUID(),UUID.randomUUID(),"lp-"+attempt,25,OffsetDateTime.now().plusMinutes(1));
    V1Job job() { return new V1Job().metadata(new V1ObjectMeta().name(ticket.workerName()).namespace("runners").uid("fixture-job").resourceVersion("123")
            .labels(Map.of("loadpilot.dev/run",ticket.runId().toString(),"loadpilot.dev/attempt",attempt.toString(),"loadpilot.dev/shard",ticket.shardId().toString()))
            .annotations(Map.of("loadpilot.dev/snapshot",ticket.snapshotId().toString(),"loadpilot.dev/submit-operation",ticket.submitOperationId().toString())))
            .status(new V1JobStatus().conditions(List.of(new V1JobCondition().type("Complete").status("True")))); }
    class Fixture implements AutoCloseable {
        final HttpsServer server;final ExecutorService executor=Executors.newFixedThreadPool(2);
        final List<String> requests=new CopyOnWriteArrayList<>();String mode="normal";int reads;
        final String ca;
        volatile V1Job overrideJob;volatile List<V1Pod> overridePods;volatile boolean allowDelete,deleted;
        Fixture() throws Exception {
            var certificate=new HeldCertificate.Builder().commonName("worker-fixture").addSubjectAlternativeName("127.0.0.1").certificateAuthority(0).build();ca=certificate.certificatePem();
            var identity=new HandshakeCertificates.Builder().heldCertificate(certificate).build();
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(identity.sslContext()));server.setExecutor(executor);
            server.createContext("/",exchange->{
                String path=exchange.getRequestURI().getPath();requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());
                int status=200;String body;
                if((!exchange.getRequestMethod().equals("GET") && !(allowDelete && exchange.getRequestMethod().equals("DELETE"))) || !"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {status=403;body="private upstream value";}
                else if(exchange.getRequestMethod().equals("DELETE")) {
                    var options=JSON.<V1DeleteOptions>deserialize(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),V1DeleteOptions.class);
                    if(overrideJob==null || options.getPreconditions()==null || !overrideJob.getMetadata().getUid().equals(options.getPreconditions().getUid())) {status=409;body="{}";}
                    else {deleted=true;body="{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Success\",\"code\":200}";}
                }
                else if(deleted && path.contains("/jobs/")) {status=404;body="{}";}
                else if(mode.equals("forbidden")) {status=403;body="private upstream value";}
                else if(mode.equals("absent") || (mode.startsWith("removed") && path.contains("/jobs/")) || (mode.equals("reappeared") && path.contains("/jobs/") && ++reads==1)) {status=404;body="private upstream value";}
                else if(mode.equals("redirect")) {status=302;body="";exchange.getResponseHeaders().set("Location",origin()+"/trap");}
                else if(mode.equals("oversize")) body="x".repeat(262145);
                else if(mode.equals("slow")) {try {Thread.sleep(3000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}body="{}";}
                else if(path.contains("/jobs/")) {var observed=overrideJob==null?job():overrideJob;if(mode.equals("changed")) observed.getMetadata().resourceVersion(Integer.toString(++reads));body=JSON.serialize(observed);}
                else {
                    var items=new ArrayList<V1Pod>();
                    if(!deleted && overridePods!=null) items.addAll(overridePods);
                    if(mode.equals("removed-live")) items.add(new V1Pod().metadata(new V1ObjectMeta().name(ticket.workerName()+"-pod").namespace("runners").uid("pod-uid").labels(job().getMetadata().getLabels())
                            .ownerReferences(List.of(new V1OwnerReference().apiVersion("batch/v1").controller(true).kind("Job").name(ticket.workerName()).uid("fixture-job"))))
                            .spec(new V1PodSpec().containers(List.of(new V1Container().name("k6")))).status(new V1PodStatus().phase("Running")));
                    body=JSON.serialize(new V1PodList().metadata(new V1ListMeta().resourceVersion("123")._continue(mode.equals("partial") || mode.equals("removed-partial")?"next":null)).items(items));
                }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                try {exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}finally {exchange.close();}
            });server.start();
        }
        String origin() {return "https://127.0.0.1:"+server.getAddress().getPort();}
        ApiClient client() {var client=new ApiClient().setBasePath(origin()).setSslCaCert(new ByteArrayInputStream(ca.getBytes(StandardCharsets.UTF_8)));client.addDefaultHeader("Authorization","Bearer fixture-token");return client;}
        @Override public void close() {server.stop(0);executor.shutdownNow();}
    }
    @Test void makesOnlyThreeReadRequestsAndReturnsCorrelatedEvidence() throws Exception {
        try(var fixture=new Fixture();var collector=new KubernetesWorkerCollector(fixture.client())) {
            var observation=collector.collect(ticket,job(),"fixture-job");
            assertThat(observation.state()).isEqualTo(RunLifecycle.WorkerState.SUCCEEDED);assertThat(observation.allWorkersTerminated()).isTrue();
            assertThat(fixture.requests).hasSize(3).allMatch(request->request.startsWith("GET "));
            assertThat(fixture.requests.get(1)).contains("/api/v1/namespaces/runners/pods?").contains("limit=17");
        }
    }
    @Test void finalOutputUsesTerminatedPodMessageOnlyAndRejectsMissingAmbiguousRestartedOrDriftedWorkers() throws Exception {
        var factory=new K6JobPlanTest();var f=factory.fixture(factory.steady(),"export default function() {}");
        var planned=factory.planner.construct(f.snapshot(),f.ticket(),factory.policy,factory.destination,java.time.Instant.parse("2026-11-06T00:00:00Z")).job();
        try(var fixture=new Fixture();var collector=new KubernetesWorkerCollector(fixture.client())) {
            fixture.overrideJob=JSON.deserialize(JSON.serialize(planned),V1Job.class);fixture.overrideJob.getMetadata().uid("final-job").resourceVersion("1");
            fixture.overrideJob.status(new V1JobStatus().conditions(List.of(new V1JobCondition().type("Complete").status("True"))));
            var now=OffsetDateTime.now(java.time.ZoneOffset.UTC);String message=new K6TerminalSummaryTest().message(f).toString();
            var ended=new V1ContainerStateTerminated().startedAt(now.minusSeconds(20)).finishedAt(now).exitCode(0).message(message);
            var worker=new V1Pod().metadata(new V1ObjectMeta().name(f.ticket().workerName()+"-pod").namespace(factory.policy.namespace()).uid("final-pod").labels(planned.getMetadata().getLabels())
                    .ownerReferences(List.of(new V1OwnerReference().apiVersion("batch/v1").kind("Job").controller(true).name(f.ticket().workerName()).uid("final-job"))))
                    .spec(JSON.<V1Job>deserialize(JSON.serialize(planned),V1Job.class).getSpec().getTemplate().getSpec())
                    .status(new V1PodStatus().phase("Succeeded").containerStatuses(List.of(new V1ContainerStatus().name("k6").image(factory.policy.imageDigest()).imageID("sha256:"+"a".repeat(64)).ready(false).restartCount(0).state(new V1ContainerState().terminated(ended)))));
            fixture.overridePods=List.of(worker);
            var data=collector.collectFinal(f.ticket(),planned,"final-job",factory.json);
            assertThat(data.podUid()).isEqualTo("final-pod");assertThat(data.summary().httpRequests()).isEqualTo(100);assertThat(fixture.requests).hasSize(3).noneMatch(request->request.contains("/log"));
            for(String change:List.of("missing","truncated","restart","image","policy","multiple","not-terminated")) {
                ended.message(message);worker.getStatus().getContainerStatuses().getFirst().restartCount(0);worker.getSpec().getContainers().getFirst().image(factory.policy.imageDigest()).terminationMessagePolicy("File");worker.getStatus().phase("Succeeded");fixture.overridePods=List.of(worker);
                switch(change) {
                    case "missing"->ended.message(null);
                    case "truncated"->ended.message(message.substring(0,message.length()-1));
                    case "restart"->worker.getStatus().getContainerStatuses().getFirst().restartCount(1);
                    case "image"->worker.getSpec().getContainers().getFirst().image("private-token.invalid/unapproved");
                    case "policy"->worker.getSpec().getContainers().getFirst().terminationMessagePolicy("FallbackToLogsOnError");
                    case "multiple"->{var another=JSON.<V1Pod>deserialize(JSON.serialize(worker),V1Pod.class);another.getMetadata().uid("second-pod");fixture.overridePods=List.of(worker,another);}
                    case "not-terminated"->worker.getStatus().phase("Running");
                }
                assertThatThrownBy(()->collector.collectFinal(f.ticket(),planned,"final-job",factory.json)).as(change).isInstanceOf(KubernetesWorkerCollector.Problem.class).hasMessageNotContaining("private-token");
            }
        }
    }
    @Test void partialListingUidAndVersionChangesDoNotProduceEvidence() throws Exception {
        try(var fixture=new Fixture();var collector=new KubernetesWorkerCollector(fixture.client())) {
            fixture.mode="partial";
            assertThatThrownBy(()->collector.collect(ticket,job(),"fixture-job")).hasMessage("Worker Pod listing is incomplete or exceeds the collection bound.");
            fixture.mode="changed";
            assertThatThrownBy(()->collector.collect(ticket,job(),"fixture-job")).hasMessage("Worker changed during collection; reobserve before updating the run.");
            assertThatThrownBy(()->collector.collect(ticket,job(),"other-job")).hasMessage("Worker Job identity changed; retain recovery protection.");
        }
    }
    @Test void permissionsAbsenceRedirectAndOversizeRemainUnconfirmedWithoutPrivateDetails() throws Exception {
        try(var fixture=new Fixture();var collector=new KubernetesWorkerCollector(fixture.client())) {
            for(String mode:List.of("forbidden","absent","redirect","oversize")) {
                fixture.mode=mode;fixture.requests.clear();
                assertThatThrownBy(()->collector.collect(ticket,job(),"fixture-job")).isInstanceOf(KubernetesWorkerCollector.Problem.class).hasMessageNotContaining("private upstream value");
                assertThat(fixture.requests).hasSize(1).noneMatch(request->request.contains("/trap"));
            }
        }
    }
    @Test void timeoutAndUnverifiedTlsDoNotProduceObservations() throws Exception {
        try(var fixture=new Fixture();var collector=new KubernetesWorkerCollector(fixture.client())) {
            fixture.mode="slow";
            assertThatThrownBy(()->collector.collect(ticket,job(),"fixture-job")).isInstanceOfSatisfying(KubernetesWorkerCollector.Problem.class,error->assertThat(error.status()).isEqualTo(503));
            var unsafe=fixture.client().setVerifyingSsl(false);
            assertThatThrownBy(()->new KubernetesWorkerCollector(unsafe)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void confirmedAbsenceRequiresTwoMissingJobReadsAndCompletePodEvidence() throws Exception {
        try(var fixture=new Fixture();var collector=new KubernetesWorkerCollector(fixture.client())) {
            fixture.mode="removed";var evidence=collector.confirmAbsent(ticket,job(),"fixture-job");
            assertThat(evidence.allWorkersTerminated()).isTrue();assertThat(evidence.state()).isEqualTo(RunLifecycle.WorkerState.TERMINATED);
            assertThat(fixture.requests).hasSize(3).allMatch(request->request.startsWith("GET "));
            fixture.mode="removed-live";
            assertThat(collector.confirmAbsent(ticket,job(),"fixture-job").allWorkersTerminated()).isFalse();
            fixture.mode="removed-partial";
            assertThatThrownBy(()->collector.confirmAbsent(ticket,job(),"fixture-job")).isInstanceOf(KubernetesWorkerCollector.Problem.class);
            fixture.mode="reappeared";fixture.reads=0;
            assertThatThrownBy(()->collector.confirmAbsent(ticket,job(),"fixture-job")).hasMessage("Worker still exists; absence is not confirmed.");
            assertThatThrownBy(()->collector.confirmAbsent(ticket,job(),null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
