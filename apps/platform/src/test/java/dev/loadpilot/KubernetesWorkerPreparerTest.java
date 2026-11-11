package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import dev.loadpilot.integrations.k6.*;
import io.kubernetes.client.openapi.ApiClient;
import jakarta.validation.Validation;
import okhttp3.tls.*;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KubernetesWorkerPreparerTest {
    final ObjectMapper json=new ObjectMapper();
    final K6JobPlanTest owner=new K6JobPlanTest();
    final String source="import http from 'k6/http';\nexport default function () { http.get('https://fixture.invalid'); }\n";

    record Prepared(K6PlanEnvelope.Prepared plan,UUID submit,String worker) {}
    Prepared plan() throws Exception {
        var fixture=owner.fixture(owner.steady(),source);
        var binding=new ExecutionClusterClients.Binding(fixture.snapshot().inputs().target().id(),1,"https://cluster.invalid:6443",List.of("192.0.2.20"),owner.policy.namespace(),List.of("READ","STOP"),"a".repeat(64));
        var envelope=new K6PlanEnvelope(json,Validation.buildDefaultValidatorFactory().getValidator()).prepare(fixture.snapshot(),fixture.ticket(),owner.policy,owner.destination,binding,owner.now);
        return new Prepared(envelope,fixture.ticket().submitOperationId(),fixture.ticket().workerName());
    }
    K6PlanEnvelope.Prepared altered(Prepared prepared,java.util.function.Consumer<ObjectNode> change) throws Exception {
        var value=(ObjectNode)json.readTree(prepared.plan().canonicalJson());change.accept(value);String text=json.writeValueAsString(value);return new K6PlanEnvelope.Prepared(text,K6PlanEnvelope.hash(text));
    }
    class Fixture implements AutoCloseable {
        final HttpsServer server;final ExecutorService executor=Executors.newFixedThreadPool(2);final String ca;
        final List<String> requests=new CopyOnWriteArrayList<>(),bodies=new CopyOnWriteArrayList<>();
        final List<String> authorization=new CopyOnWriteArrayList<>();
        volatile int status=201;volatile String mutation="";
        Fixture() throws Exception {
            var cert=new HeldCertificate.Builder().commonName("prepare-fixture").addSubjectAlternativeName("127.0.0.1").certificateAuthority(0).build();ca=cert.certificatePem();
            var identity=new HandshakeCertificates.Builder().heldCertificate(cert).build();server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(identity.sslContext()));server.setExecutor(executor);
            server.createContext("/",exchange->{
                requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());
                authorization.add(Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),""));
                String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);bodies.add(input);
                byte[] response;
                if(status==201) {
                    var body=(ObjectNode)json.readTree(input);((ObjectNode)body.path("metadata")).put("uid","created-resource-uid").put("resourceVersion","1");
                    if("NetworkPolicy".equals(body.path("kind").asText()))((ObjectNode)body.path("spec")).remove("ingress");
                    switch(mutation) {
                        case "uid" -> ((ObjectNode)body.path("metadata")).remove("uid");
                        case "namespace" -> ((ObjectNode)body.path("metadata")).put("namespace","other");
                        case "trace" -> ((ObjectNode)body.path("metadata").path("annotations")).remove("loadpilot.dev/submit-operation");
                        case "suspension" -> ((ObjectNode)body.path("spec")).put("suspend",false);
                        case "files" -> ((ObjectNode)body.path("data")).put("scenario.js","private upstream changed source");
                        case "mutable" -> body.put("immutable",false);
                        case "network" -> ((ObjectNode)body.path("spec")).putArray("egress");
                    }
                    response=mutation.equals("invalid")?"not JSON private upstream detail".getBytes(StandardCharsets.UTF_8):mutation.equals("oversized")?(" ".repeat(262145)).getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(body);
                } else {
                    if(status==302) exchange.getResponseHeaders().set("Location",origin()+"/trap");
                    response="{\"message\":\"private upstream detail\"}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,response.length);
                try {exchange.getResponseBody().write(response);} finally {exchange.close();}
            });server.start();
        }
        String origin(){return "https://127.0.0.1:"+server.getAddress().getPort();}
        ApiClient client(){return new ApiClient().setBasePath(origin()).setSslCaCert(new ByteArrayInputStream(ca.getBytes(StandardCharsets.UTF_8)));}
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
    @Test void createsOnlyTheRequestedResourceAndKeepsTheJobSuspended() throws Exception {
        var p=plan();
        try(var fixture=new Fixture();var adapter=new KubernetesWorkerPreparer(fixture.client(),json)) {
            for(var resource:KubernetesWorkerPreparer.Resource.values()) {
                var receipt=adapter.create(p.plan(),p.submit(),resource);
                assertThat(receipt).isEqualTo(new KubernetesWorkerPreparer.Receipt(resource,owner.policy.namespace(),p.worker(),"created-resource-uid"));
            }
            assertThat(fixture.requests).containsExactly("POST /api/v1/namespaces/loadpilot-runners/configmaps","POST /apis/networking.k8s.io/v1/namespaces/loadpilot-runners/networkpolicies","POST /apis/batch/v1/namespaces/loadpilot-runners/jobs");
            var files=json.readTree(fixture.bodies.get(0));assertThat(files.path("immutable").asBoolean()).isTrue();assertThat(files.path("data").path("scenario.js").asText()).isEqualTo(source);
            var network=json.readTree(fixture.bodies.get(1));assertThat(network.path("spec").path("ingress").isArray()).isTrue();assertThat(network.path("spec").path("egress").size()).isEqualTo(1);assertThat(network.path("spec").path("egress").get(0).path("to").get(0).path("ipBlock").path("cidr").asText()).isEqualTo("192.0.2.10/32");
            var job=json.readTree(fixture.bodies.get(2));assertThat(job.path("spec").path("suspend").asBoolean()).isTrue();assertThat(job.path("spec").path("backoffLimit").asInt()).isZero();
            assertThat(job.path("metadata").path("annotations").path("loadpilot.dev/submit-operation").asText()).isEqualTo(p.submit().toString());
        }
    }
    @Test void rejectsUnboundActiveCorruptOrMismatchedPlansBeforeNetworkIo() throws Exception {
        var p=plan();
        try(var fixture=new Fixture();var adapter=new KubernetesWorkerPreparer(fixture.client(),json)) {
            var bad=new ArrayList<K6PlanEnvelope.Prepared>();
            bad.add(new K6PlanEnvelope.Prepared(p.plan().canonicalJson(),"0".repeat(64)));
            bad.add(altered(p,body->body.put("schemaVersion","k6-execution-plan-v1")));
            bad.add(altered(p,body->body.put("plannerVersion","job-planner-v1")));
            bad.add(altered(p,body->((ObjectNode)body.path("job").path("spec")).put("suspend",false)));
            bad.add(altered(p,body->((ObjectNode)body.path("files")).put("immutable",false)));
            bad.add(altered(p,body->((ObjectNode)body.path("network").path("metadata")).put("namespace","other")));
            for(var plan:bad) assertThatThrownBy(()->adapter.create(plan,p.submit(),KubernetesWorkerPreparer.Resource.JOB)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->adapter.create(p.plan(),UUID.randomUUID(),KubernetesWorkerPreparer.Resource.FILES)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->adapter.create(new K6PlanEnvelope.Prepared(null,null),p.submit(),KubernetesWorkerPreparer.Resource.JOB)).isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.requests).isEmpty();
        }
    }
    @Test void conflictDeniedRedirectAndUncertainOutcomesNeverRetryOrAdopt() throws Exception {
        var p=plan();
        try(var fixture=new Fixture();var adapter=new KubernetesWorkerPreparer(fixture.client(),json)) {
            for(int status:List.of(409,401,403,302,500)) {
                fixture.status=status;fixture.requests.clear();
                assertThatThrownBy(()->adapter.create(p.plan(),p.submit(),KubernetesWorkerPreparer.Resource.JOB)).isInstanceOfSatisfying(KubernetesWorkerPreparer.Problem.class,problem->assertThat(problem.status()).isEqualTo(status==409?409:(status==401 || status==403)?403:503)).hasMessageNotContaining("private upstream");
                assertThat(fixture.requests).containsExactly("POST /apis/batch/v1/namespaces/loadpilot-runners/jobs");
            }
        }
    }
    @Test void alteredOrIncompleteAcknowledgementsCannotProduceAReceipt() throws Exception {
        var p=plan();
        try(var fixture=new Fixture();var adapter=new KubernetesWorkerPreparer(fixture.client(),json)) {
            var cases=new LinkedHashMap<String,KubernetesWorkerPreparer.Resource>();
            for(String mutation:List.of("uid","namespace","trace","suspension","invalid","oversized")) cases.put(mutation,KubernetesWorkerPreparer.Resource.JOB);
            cases.put("files",KubernetesWorkerPreparer.Resource.FILES);cases.put("mutable",KubernetesWorkerPreparer.Resource.FILES);cases.put("network",KubernetesWorkerPreparer.Resource.NETWORK);
            for(var entry:cases.entrySet()) {
                fixture.mutation=entry.getKey();fixture.requests.clear();
                assertThatThrownBy(()->adapter.create(p.plan(),p.submit(),entry.getValue())).as(entry.getKey()).isInstanceOfSatisfying(KubernetesWorkerPreparer.Problem.class,problem->assertThat(problem.status()).isEqualTo(503)).hasMessageNotContaining("private upstream");
                assertThat(fixture.requests).hasSize(1);
            }
        }
    }
}
