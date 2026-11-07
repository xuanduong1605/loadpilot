package dev.loadpilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import dev.loadpilot.integrations.ConnectionStore;
import dev.loadpilot.integrations.k6.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.Request;
import okhttp3.tls.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesProbeCollectorTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Instant now=Instant.parse("2026-11-06T00:00:00Z");
    final RuntimeProbePlan builder=new RuntimeProbePlan(json);
    class Fixture implements AutoCloseable {
        final HttpsServer server;final ExecutorService executor=Executors.newFixedThreadPool(2);final String ca;
        final Map<String,String> reads=new ConcurrentHashMap<>();final List<String> requests=new CopyOnWriteArrayList<>();
        final ExecutionClusterClients clients;final ExecutionClusterClients.Binding binding;final ExecutionClusterClients.Scope scope;
        final K6PlanEnvelope.Prepared original;final RuntimeProbePlan.Prepared plan;final JsonNode body;final List<String> paths;final Clock clock;
        volatile Runnable beforeRead=()->{};
        Fixture(RuntimeProbePlan.Phase phase,List<String> operations) throws Exception {
            var cert=new HeldCertificate.Builder().commonName("probe-fixture").addSubjectAlternativeName("127.0.0.1").certificateAuthority(0).build();ca=cert.certificatePem();
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslContext()));server.setExecutor(executor);
            server.createContext("/",exchange->{String path=exchange.getRequestURI().toString();requests.add(exchange.getRequestMethod()+" "+path);beforeRead.run();String response=reads.get(path);int status=response==null?404:200;byte[] bytes=(response==null?"{}":response).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",path.contains("/log?")?"text/plain":"application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
            var saved=new KubernetesWorkerPreparerTest().plan().plan();var value=(ObjectNode)json.readTree(saved.canonicalJson());UUID target=UUID.fromString(value.path("clusterBinding").path("targetId").asText());String ns=value.path("policy").path("namespace").asText();var store=mock(ConnectionStore.class);
            when(store.view(target)).thenReturn(new ConnectionStore.View(1,origin(),ca,true,"","",false,null));when(store.kubernetesMaterial(target)).thenReturn(new ConnectionStore.KubernetesMaterial(1,origin(),ca,"fixture-token"));
            clients=new ExecutionClusterClients(json.writeValueAsString(List.of(Map.of("targetId",target.toString(),"connectionRevision",1,"origin",origin(),"addresses",List.of("127.0.0.1"),"namespace",ns,"operations",operations))),json,store);binding=clients.binding(target,ns);value.set("clusterBinding",json.valueToTree(binding));String text=value.toString();original=new K6PlanEnvelope.Prepared(text,K6PlanEnvelope.hash(text));
            plan=builder.construct(original,UUID.randomUUID(),new RuntimeProbePlan.Node("node-a","node-uid"),new RuntimeProbePlan.Canary("http://probe.invalid:8080","192.0.2.33","/ready","/stall"),"https://fixture.invalid/ready",phase,now);body=builder.verify(original,plan);String name=body.path("job").path("metadata").path("name").asText(),podName=name+"-abc";paths=KubernetesProbeCollector.paths(body,podName);
            scope=new ExecutionClusterClients.Scope(target,1,ns,name,null,ExecutionClusterClients.Access.PROBE_READ);clock=Clock.fixed(phase==RuntimeProbePlan.Phase.CLOCK?now.plusSeconds(2):now.plusSeconds(30),ZoneOffset.UTC);
            for(int i=0;i<3;i++){String field=List.of("files","network","job").get(i);var resource=(ObjectNode)body.path(field).deepCopy();((ObjectNode)resource.path("metadata")).put("uid",field+"-uid").put("resourceVersion","1");reads.put(paths.get(i),resource.toString());}
            reads.put(paths.get(3),"{\"metadata\":{\"name\":\"node-a\",\"uid\":\"node-uid\",\"resourceVersion\":\"1\"},\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}]}}");
            var job=(ObjectNode)json.readTree(reads.get(paths.get(2)));boolean negative=Set.of(RuntimeProbePlan.Phase.LATE_GUARD,RuntimeProbePlan.Phase.EXPIRED_GUARD,RuntimeProbePlan.Phase.DEADLINE).contains(phase);
            if(phase!=RuntimeProbePlan.Phase.CLOCK)job.putObject("status").putArray("conditions").addObject().put("type",negative?"Failed":"Complete").put("status","True").put("reason",phase==RuntimeProbePlan.Phase.DEADLINE?"DeadlineExceeded":negative?"BackoffLimitExceeded":"CompletionsReached");reads.put(paths.get(2),job.toString());
            var pod=json.createObjectNode();var meta=pod.putObject("metadata");meta.put("name",podName).put("namespace",ns).put("uid","pod-uid").put("resourceVersion","1");meta.set("labels",body.path("job").path("spec").path("template").path("metadata").path("labels"));meta.set("annotations",body.path("job").path("spec").path("template").path("metadata").path("annotations"));meta.putArray("ownerReferences").addObject().put("name",name).put("kind","Job").put("uid","job-uid").put("controller",true);
            pod.set("spec",body.path("job").path("spec").path("template").path("spec").deepCopy());((ObjectNode)pod.path("spec")).put("nodeName","node-a");var status=pod.putObject("status");status.put("startTime",now.plusSeconds(1).toString()).put("phase",phase==RuntimeProbePlan.Phase.CLOCK?"Running":negative?"Failed":"Succeeded");if(phase==RuntimeProbePlan.Phase.DEADLINE)status.put("reason","DeadlineExceeded");
            var container=status.putArray("containerStatuses").addObject();container.put("name","k6").put("restartCount",0).put("imageID",body.path("job").path("spec").path("template").path("spec").path("containers").get(0).path("image").asText());
            if(phase==RuntimeProbePlan.Phase.CLOCK){container.putObject("state").putObject("running").put("startedAt",now.plusSeconds(1).toString());reads.put(paths.get(6),now.plusSeconds(2)+" LP_PROBE_CLOCK:"+body.path("probeId").asText()+":node-uid:"+now.plusSeconds(2).toEpochMilli()+"\n");}
            else{
                int deadline=body.path("job").path("spec").path("template").path("spec").path("activeDeadlineSeconds").asInt();var ended=container.putObject("state").putObject("terminated");ended.put("startedAt",now.plusSeconds(1).toString()).put("finishedAt",now.plusSeconds(phase==RuntimeProbePlan.Phase.DEADLINE?deadline+1:2).toString()).put("exitCode",phase==RuntimeProbePlan.Phase.DEADLINE?143:negative?108:0).put("reason","private-error-reason");
                ended.put("message",negative?"private-error-message":summary(body,phase).toString());
            }
            reads.put(paths.get(5),pod.toString());listPod(pod);
        }
        void listPod(JsonNode pod){reads.put(paths.get(4),json.createObjectNode().set("metadata",json.createObjectNode()).toString());var list=json.createObjectNode();list.putObject("metadata");list.putArray("items").add(pod);reads.put(paths.get(4),list.toString());}
        String origin(){return "https://127.0.0.1:"+server.getAddress().getPort();}
        KubernetesProbeCollector collector(){return new KubernetesProbeCollector(clients,json,clock);}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
    ObjectNode summary(JsonNode body,RuntimeProbePlan.Phase phase){
        boolean guard=phase==RuntimeProbePlan.Phase.VALID_GUARD,blocked=phase==RuntimeProbePlan.Phase.BLOCKED,timeout=phase==RuntimeProbePlan.Phase.TIMEOUT;var value=json.createObjectNode();
        value.put("schemaVersion","runtime-probe-message-v1").put("probeId",body.path("probeId").asText()).put("nodeUid","node-uid").put("phase",phase.name()).put("attempts",guard?0:3).put("successes",guard||blocked||timeout?0:3).put("responses",guard||blocked||timeout?0:3).put("timeouts",timeout?3:0).put("iterations",guard?1:0);
        if(guard){value.putNull("minimumElapsedMs");value.putNull("maximumElapsedMs");}else{value.put("minimumElapsedMs",timeout?1000:10).put("maximumElapsedMs",timeout?1000:20);}return value;
    }
    @Test void allPhasesProduceIdentityBoundSanitizedArtifactsThroughReadOnlyTls() throws Exception {
        for(var phase:RuntimeProbePlan.Phase.values())try(var f=new Fixture(phase,List.of("PROBE_READ"))){
            var capture=f.collector().collect(f.original,f.plan);assertThat(capture.planSha256()).isEqualTo(f.plan.sha256());assertThat(capture.artifactJson()).doesNotContain("private-error","fixture-token","LP_PROBE_CLOCK");
            var value=json.readTree(capture.artifactJson());assertThat(value.path("receipts").path("jobUid").asText()).isEqualTo("job-uid");assertThat(value.path("phase").asText()).isEqualTo(phase.name());assertThat(value.path("observedHashes").size()).isEqualTo(5);
            assertThat(f.requests).hasSize(phase==RuntimeProbePlan.Phase.CLOCK?11:10).allMatch(request->request.startsWith("GET "));
        }
    }
    @Test void wrongSourceOwnershipNodeRuntimeImageRestartOrPaginationNeverBecomeEvidence() throws Exception {
        for(String mutation:List.of("source","owner","node","image","restart","page","security","unknown-spec","second-uid"))try(var f=new Fixture(RuntimeProbePlan.Phase.ALLOWED,List.of("PROBE_READ"))){
            var pod=(ObjectNode)json.readTree(f.reads.get(f.paths.get(5)));
            switch(mutation){
                case "source"->{var files=(ObjectNode)json.readTree(f.reads.get(f.paths.get(0)));((ObjectNode)files.path("data")).put("scenario.js","private-value");f.reads.put(f.paths.get(0),files.toString());}
                case "owner"->((ObjectNode)pod.path("metadata").path("ownerReferences").get(0)).put("uid","foreign");
                case "node"->((ObjectNode)pod.path("spec")).put("nodeName","foreign-node");
                case "image"->((ObjectNode)pod.path("status").path("containerStatuses").get(0)).put("imageID","foreign@sha256:"+"c".repeat(64));
                case "restart"->((ObjectNode)pod.path("status").path("containerStatuses").get(0)).put("restartCount",1);
                case "page"->{var list=(ObjectNode)json.readTree(f.reads.get(f.paths.get(4)));((ObjectNode)list.path("metadata")).put("continue","more");f.reads.put(f.paths.get(4),list.toString());}
                case "security"->((ObjectNode)pod.path("spec").path("containers").get(0).path("securityContext")).put("allowPrivilegeEscalation",true);
                case "unknown-spec"->((ObjectNode)pod.path("spec")).put("foreignUnsafeField",true);
                case "second-uid"->f.beforeRead=()->{if(f.requests.getLast().equals("GET "+f.paths.get(5))){var changed=pod.deepCopy();((ObjectNode)changed.path("metadata")).put("uid","replacement");f.reads.put(f.paths.get(5),changed.toString());}};
            }
            if(!Set.of("source","page").contains(mutation)){f.reads.put(f.paths.get(5),pod.toString());f.listPod(pod);}
            assertThatThrownBy(()->f.collector().collect(f.original,f.plan)).as(mutation).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-value");
        }
    }
    @Test void blockedHttpErrorsStillProveReachabilityAndTimeoutMustBeTheMeasuredError() throws Exception {
        for(var phase:List.of(RuntimeProbePlan.Phase.BLOCKED,RuntimeProbePlan.Phase.TIMEOUT))try(var f=new Fixture(phase,List.of("PROBE_READ"))){
            var pod=(ObjectNode)json.readTree(f.reads.get(f.paths.get(5)));var summary=summary(f.body,phase);summary.put("responses",3);((ObjectNode)pod.path("status").path("containerStatuses").get(0).path("state").path("terminated")).put("message",summary.toString());f.reads.put(f.paths.get(5),pod.toString());f.listPod(pod);
            assertThatThrownBy(()->f.collector().collect(f.original,f.plan)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void summaryShapeCorrelationAndClockSamplesAreStrict() throws Exception {
        for(String mutation:List.of("nonce","extra","fraction","zero","stale-clock","foreign-clock"))try(var f=new Fixture(mutation.contains("clock")?RuntimeProbePlan.Phase.CLOCK:RuntimeProbePlan.Phase.ALLOWED,List.of("PROBE_READ"))){
            if(mutation.contains("clock")){f.reads.put(f.paths.get(6),now+" LP_PROBE_CLOCK:"+(mutation.equals("foreign-clock")?UUID.randomUUID():f.body.path("probeId").asText())+":node-uid:"+now.minusSeconds(20).toEpochMilli()+"\n");}
            else{var pod=(ObjectNode)json.readTree(f.reads.get(f.paths.get(5)));var summary=summary(f.body,RuntimeProbePlan.Phase.ALLOWED);switch(mutation){case "nonce"->summary.put("probeId",UUID.randomUUID().toString());case "extra"->summary.put("private","private-value");case "fraction"->summary.put("successes",2.9);case "zero"->summary.put("attempts",0);}((ObjectNode)pod.path("status").path("containerStatuses").get(0).path("state").path("terminated")).put("message",summary.toString());f.reads.put(f.paths.get(5),pod.toString());f.listPod(pod);}
            assertThatThrownBy(()->f.collector().collect(f.original,f.plan)).as(mutation).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-value");
        }
    }
    @Test void probePermissionIsExplicitAndCannotReadOtherPathsOrWriteBeforeTransmission() throws Exception {
        try(var f=new Fixture(RuntimeProbePlan.Phase.ALLOWED,List.of("READ","STOP","PREPARE","METRICS","ACTIVATE","RECOVER","RUNTIME"))){assertThatThrownBy(()->f.collector().collect(f.original,f.plan)).isInstanceOf(IllegalStateException.class);assertThat(f.requests).isEmpty();}
        try(var f=new Fixture(RuntimeProbePlan.Phase.ALLOWED,List.of("PROBE_READ"))){
            assertThatThrownBy(()->f.clients.openBound(f.scope,f.binding)).isInstanceOf(ExecutionClusterClients.Problem.class);
            assertThatThrownBy(()->f.clients.openProbeRead(f.scope,f.binding,f.original,f.plan,"/api/v1/secrets",null)).isInstanceOf(ExecutionClusterClients.Problem.class);
            try(var session=f.clients.openProbeRead(f.scope,f.binding,f.original,f.plan,f.paths.get(0),null)){
                for(var request:List.of(new Request.Builder().url(f.origin()+f.paths.get(0)).delete().build(),new Request.Builder().url(f.origin()+f.paths.get(0)+"?extra=true").build(),new Request.Builder().url(f.origin()+f.paths.get(1)).build()))
                    assertThatThrownBy(()->session.client().getHttpClient().newCall(request).execute()).isInstanceOf(java.io.IOException.class);
            }
            assertThat(f.requests).isEmpty();
        }
    }
    @Test void connectedWorkflowRequiresDurableIntentAndReplaysStoredEvidenceWithoutClusterAccess(@TempDir Path directory) throws Exception {
        try(var f=new Fixture(RuntimeProbePlan.Phase.ALLOWED,List.of("PROBE_READ"))){
            var archive=new RuntimeProbeArchive(directory,json);var service=new RuntimeProbeEvidenceService(builder,archive,f.collector(),Clock.fixed(now,ZoneOffset.UTC));
            assertThatThrownBy(()->service.capture(f.original,f.plan)).isInstanceOf(IllegalStateException.class);assertThat(f.requests).isEmpty();
            var prepared=service.prepare(f.original,UUID.fromString(f.body.path("probeId").asText()),new RuntimeProbePlan.Node("node-a","node-uid"),new RuntimeProbePlan.Canary("http://probe.invalid:8080","192.0.2.33","/ready","/stall"),"https://fixture.invalid/ready",RuntimeProbePlan.Phase.ALLOWED);
            assertThat(prepared).isEqualTo(f.plan);assertThat(f.requests).isEmpty();var receipt=service.capture(f.original,prepared);assertThat(f.requests).hasSize(10);assertThat(receipt.artifactJson()).doesNotContain("private-error","fixture-token");
            f.reads.clear();var restarted=new RuntimeProbeEvidenceService(builder,new RuntimeProbeArchive(directory,json),f.collector(),Clock.fixed(now,ZoneOffset.UTC));assertThat(restarted.capture(f.original,prepared)).isEqualTo(receipt);assertThat(f.requests).hasSize(10);
            Files.writeString(directory.resolve(prepared.sha256()+".capture.json"),"private-corrupt-receipt");assertThatThrownBy(()->restarted.capture(f.original,prepared)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-corrupt-receipt");assertThat(f.requests).hasSize(10);
        }
    }
}
