package dev.loadpilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import dev.loadpilot.integrations.ConnectionStore;
import dev.loadpilot.integrations.k6.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.Request;
import okhttp3.tls.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignedRuntimeAdmissionTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Instant now=Instant.parse("2026-11-06T00:00:00Z");
    @TempDir Path directory;
    final KeyPair key=key();
    static KeyPair key(){try{return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}catch(Exception failed){throw new IllegalStateException(failed);}}
    String keys() throws Exception{return json.writeValueAsString(Map.of("fixture",Base64.getEncoder().encodeToString(key.getPublic().getEncoded())));}
    SignedRuntimeEvidence evidence() throws Exception{return new SignedRuntimeEvidence(directory.toString(),keys(),json);}
    SignedRuntimeEvidence.NodeProbe probe(K6PlanEnvelope.Prepared plan) throws Exception {
        var body=json.readTree(plan.canonicalJson());var spec=body.path("job").path("spec").path("template").path("spec");
        int deadline=spec.path("activeDeadlineSeconds").asInt(),grace=spec.path("terminationGracePeriodSeconds").asInt(),timeout=body.path("requiredSourcePolicy").path("requestTimeoutMs").asInt();
        String image=body.path("policy").path("imageDigest").asText();
        return new SignedRuntimeEvidence.NodeProbe("node-a","node-uid",image,"b".repeat(64),now.minusMillis(500),now,now.minusMillis(250).toEpochMilli(),
                3,3,3,3,0,timeout,timeout,3,108,108,1,0,deadline,grace,now.minusSeconds(deadline),now,"DeadlineExceeded",143);
    }
    SignedRuntimeEvidence.Report report(K6PlanEnvelope.Prepared plan,String state) throws Exception {
        return new SignedRuntimeEvidence.Report(SignedRuntimeEvidence.VERSION,UUID.randomUUID(),SignedRuntimeEvidence.profile(plan,json),state,now,now.plusSeconds(600),List.of(probe(plan)));
    }
    void save(K6PlanEnvelope.Prepared plan,Object report,KeyPair signer) throws Exception {
        byte[] payload=json.writeValueAsBytes(report);var signature=Signature.getInstance("Ed25519");signature.initSign(signer.getPrivate());signature.update(payload);
        Files.writeString(directory.resolve(SignedRuntimeEvidence.profile(plan,json)+".json"),json.writeValueAsString(new SignedRuntimeEvidence.Envelope("fixture",Base64.getEncoder().encodeToString(payload),Base64.getEncoder().encodeToString(signature.sign()))));
    }
    ObjectNode value(Object object){return (ObjectNode)json.valueToTree(object);}
    K6PlanEnvelope.Prepared plan() throws Exception{return new KubernetesWorkerPreparerTest().plan().plan();}
    @Test void defaultBeanDeniesAndMalformedTrustConfigurationCannotEnableExecution(){
        new ApplicationContextRunner().withUserConfiguration(RuntimeAdmissionConfiguration.class)
                .withBean(ObjectMapper.class,()->json).withBean(ExecutionClusterClients.class,()->mock(ExecutionClusterClients.class))
                .run(context->{assertThat(context).hasSingleBean(RuntimeAdmission.class);assertThat(context.getBean(RuntimeAdmission.class).available()).isFalse();});
        for(String keys:List.of("{}","[]","{\"fixture\":\"private-value\"}","{\"fixture\":null}","{\"fixture\":\"a\",\"fixture\":\"b\"}"))
            assertThatThrownBy(()->new SignedRuntimeEvidence(directory.toString(),keys,json)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-value");
    }
    @Test void signatureAndNumericProbeEvidenceBindThePlanAndRejectExpiryOrTampering() throws Exception {
        var plan=plan();var report=report(plan,"a".repeat(64));save(plan,report,key);
        assertThat(evidence().load(plan,now).report()).isEqualTo(report);
        save(plan,report,key());assertThatThrownBy(()->evidence().load(plan,now)).isInstanceOf(IllegalStateException.class);
        save(plan,report,key);var envelope=value(json.readValue(Files.readString(directory.resolve(report.profileSha256()+".json")),SignedRuntimeEvidence.Envelope.class));
        envelope.put("payload",Base64.getEncoder().encodeToString(json.writeValueAsBytes(value(report).put("stateSha256","c".repeat(64)))));
        Files.writeString(directory.resolve(report.profileSha256()+".json"),envelope.toString());assertThatThrownBy(()->evidence().load(plan,now)).isInstanceOf(IllegalStateException.class);
        for(String mutation:List.of("expired","short","future","long","profile","zero","clock","timeout","egress","deadline","guard","image","duplicate","extra","coercion","fraction","null")){
            var body=value(report);var node=(ObjectNode)body.path("nodes").get(0);
            switch(mutation){
                case "expired"->body.put("validUntil",now.toString());case "short"->body.put("validUntil",now.plusSeconds(20).toString());
                case "future"->body.put("issuedAt",now.plusSeconds(1).toString());case "long"->body.put("validUntil",now.plusSeconds(901).toString());
                case "profile"->body.put("profileSha256","c".repeat(64));case "zero"->node.put("baselineAfterRequests",0);
                case "clock"->node.put("workerEpochMillis",now.plusSeconds(4).toEpochMilli());case "timeout"->node.put("timeoutElapsedMs",1);
                case "egress"->node.put("blockedResponses",1);case "deadline"->node.put("deadlineFinishedAt",now.plusSeconds(1).toString());
                case "guard"->node.put("rejectedIterations",1);case "image"->node.put("imageId","foreign.invalid/image@sha256:"+"c".repeat(64));
                case "duplicate"->((com.fasterxml.jackson.databind.node.ArrayNode)body.path("nodes")).add(node.deepCopy());case "extra"->body.put("private-field","private-value");
                case "coercion"->node.put("blockedRequests","3");case "fraction"->node.put("blockedResponses",0.1);case "null"->node.putNull("blockedResponses");
            }
            save(plan,body,key);assertThatThrownBy(()->evidence().load(plan,now)).as(mutation).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-value");
        }
    }
    @Test void explicitOperatorValiditySupportsLongerReviewedBudgetsWithoutWeakeningFreshProbeRequirements() throws Exception{
        var body=(ObjectNode)json.readTree(plan().canonicalJson());body.put("deadlineAt",now.plusSeconds(3600).toString());var plan=prepared(body);var original=report(plan,"a".repeat(64));
        var report=new SignedRuntimeEvidence.Report(original.version(),original.verificationId(),original.profileSha256(),original.stateSha256(),now,now.plusSeconds(7200),original.nodes());save(plan,report,key);
        assertThatThrownBy(()->evidence().load(plan,now)).isInstanceOf(IllegalStateException.class);
        var configured=new SignedRuntimeEvidence(directory.toString(),keys(),json,86400);assertThat(configured.load(plan,now).report()).isEqualTo(report);
        var stale=value(report);var node=(ObjectNode)stale.path("nodes").get(0);node.put("controllerBefore",now.minusSeconds(302).toString()).put("controllerAfter",now.minusSeconds(301).toString()).put("workerEpochMillis",now.minusSeconds(301).toEpochMilli());save(plan,stale,key);
        assertThatThrownBy(()->configured.load(plan,now)).isInstanceOf(IllegalStateException.class);
        for(int invalid:List.of(0,604801))assertThatThrownBy(()->new SignedRuntimeEvidence(directory.toString(),keys(),json,invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void safetyProfileIgnoresOnlyPerRunIdentityAndDetectsChangedBoundsOrRuntime() throws Exception {
        var original=plan();String profile=SignedRuntimeEvidence.profile(original,json);
        var copy=(ObjectNode)json.readTree(original.canonicalJson());copy.put("runId",UUID.randomUUID().toString()).put("workerName","lp-new-run");
        ((ObjectNode)copy.path("workerClock")).put("submissionStartedAt",now.plusSeconds(3).toString());
        assertThat(SignedRuntimeEvidence.profile(prepared(copy),json)).isEqualTo(profile);
        for(String field:List.of("image","resources","timeout","destination","clock","network")){
            var changed=(ObjectNode)json.readTree(original.canonicalJson());
            switch(field){
                case "image"->((ObjectNode)changed.path("policy")).put("imageDigest","foreign.invalid/image@sha256:"+"c".repeat(64));
                case "resources"->((ObjectNode)changed.path("job").path("spec").path("template").path("spec").path("containers").get(0).path("resources").path("limits")).put("cpu","900m");
                case "timeout"->((ObjectNode)changed.path("requiredSourcePolicy")).put("requestTimeoutMs",12000);
                case "destination"->((ObjectNode)changed.path("destination")).put("ip","192.0.2.99");
                case "clock"->((ObjectNode)changed.path("workerClock")).put("startupTimeoutSeconds",99);
                case "network"->((ObjectNode)changed.path("network").path("spec")).putArray("egress");
            }
            assertThat(SignedRuntimeEvidence.profile(prepared(changed),json)).as(field).isNotEqualTo(profile);
        }
    }
    K6PlanEnvelope.Prepared prepared(JsonNode body){String text=body.toString();return new K6PlanEnvelope.Prepared(text,K6PlanEnvelope.hash(text));}
    Map<String,JsonNode> reads(K6PlanEnvelope.Prepared plan) throws Exception {
        var body=json.readTree(plan.canonicalJson());var paths=KubernetesRuntimeState.paths(body,"network-system","cni-agent");Map<String,JsonNode> reads=new LinkedHashMap<>();
        reads.put(paths.get(0),json.readTree("{\"metadata\":{\"resourceVersion\":\"1\"},\"items\":[{\"metadata\":{\"name\":\"node-a\",\"uid\":\"node-uid\",\"labels\":{\"kubernetes.io/os\":\"linux\"}},\"spec\":{},\"status\":{\"nodeInfo\":{\"bootID\":\"boot-a\",\"containerRuntimeVersion\":\"containerd://fixture\"},\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}]}}]}"));
        for(int index:List.of(1,2,3,5)){
            String path=paths.get(index),name=path.substring(path.lastIndexOf('/')+1);var resource=json.createObjectNode();resource.putObject("metadata").put("name",name).put("uid",index==5?"cni-uid":"uid-"+index).put("generation",1);reads.put(path,resource);
        }
        ((ObjectNode)reads.get(paths.get(5))).putObject("status").put("observedGeneration",1).put("numberReady",1).put("numberUnavailable",0);
        reads.put(paths.get(4),json.readTree("{\"metadata\":{},\"items\":[]}"));
        reads.put(paths.get(6),json.readTree("{\"metadata\":{},\"items\":[{\"metadata\":{\"name\":\"cni-agent-abc\",\"uid\":\"cni-pod-uid\",\"ownerReferences\":[{\"uid\":\"cni-uid\",\"kind\":\"DaemonSet\",\"controller\":true}]},\"spec\":{\"nodeName\":\"node-a\"},\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}],\"containerStatuses\":[{\"ready\":true,\"imageID\":\"fixture-cni@sha256:bbbb\"}]}}]}"));
        return reads;
    }
    String digest(K6PlanEnvelope.Prepared plan,Map<String,JsonNode> reads) throws Exception{return KubernetesRuntimeState.digest(json.readTree(plan.canonicalJson()),reads,"network-system","cni-agent",List.of(probe(plan)),json);}
    @Test void completeReadbacksBindEveryNodeCniAndPolicyButIgnoreHeartbeatVersionChurn() throws Exception {
        var plan=plan();var reads=reads(plan);String initial=digest(plan,reads);var paths=new ArrayList<>(reads.keySet());
        ((ObjectNode)reads.get(paths.get(0)).path("items").get(0).path("metadata")).put("resourceVersion","changed");
        ((ObjectNode)reads.get(paths.get(0)).path("items").get(0).path("status").path("conditions").get(0)).put("lastHeartbeatTime",now.toString());
        assertThat(digest(plan,reads)).isEqualTo(initial);
        ((ObjectNode)reads.get(paths.get(0)).path("items").get(0).path("status").path("nodeInfo")).put("bootID","changed");assertThat(digest(plan,reads)).isNotEqualTo(initial);
        for(String mutation:List.of("new-node","page","unready","cni-image","cni-down","policy","own-policy")){
            var current=reads(plan);var p=KubernetesRuntimeState.paths(json.readTree(plan.canonicalJson()),"network-system","cni-agent");
            switch(mutation){
                case "new-node"->((com.fasterxml.jackson.databind.node.ArrayNode)current.get(p.get(0)).path("items")).add(current.get(p.get(0)).path("items").get(0).deepCopy());
                case "page"->((ObjectNode)current.get(p.get(0)).path("metadata")).put("continue","more");
                case "unready"->((ObjectNode)current.get(p.get(0)).path("items").get(0).path("status").path("conditions").get(0)).put("status","False");
                case "cni-image"->((ObjectNode)current.get(p.get(6)).path("items").get(0).path("status").path("containerStatuses").get(0)).put("imageID","changed");
                case "cni-down"->((ObjectNode)current.get(p.get(5)).path("status")).put("numberUnavailable",1);
                case "policy"->((com.fasterxml.jackson.databind.node.ArrayNode)current.get(p.get(4)).path("items")).add(json.readTree("{\"metadata\":{\"name\":\"allow-all\"},\"spec\":{\"egress\":[{}]}}"));
                case "own-policy"->((com.fasterxml.jackson.databind.node.ArrayNode)current.get(p.get(4)).path("items")).add(json.readTree(plan.canonicalJson()).path("network"));
            }
            if(Set.of("new-node","page","unready","cni-down","policy").contains(mutation))assertThatThrownBy(()->digest(plan,current)).as(mutation).isInstanceOf(IllegalStateException.class);
            else if(mutation.equals("own-policy"))assertThat(digest(plan,current)).isEqualTo(initial);
            else assertThat(digest(plan,current)).as(mutation).isNotEqualTo(initial);
        }
    }
    @Test void admissionRequiresTwoMatchingReadbacksAndRechecksTheExactPersistedGrant() throws Exception {
        var plan=plan();var report=report(plan,"a".repeat(64));save(plan,report,key);
        var state=mock(KubernetesRuntimeState.class);when(state.read(eq(plan),any())).thenReturn(report.stateSha256());
        var admission=new SignedRuntimeAdmission(evidence(),state,Clock.fixed(now,ZoneOffset.UTC));assertThat(admission.available()).isTrue();var grant=admission.verify(plan);
        assertThat(grant.planSha256()).isEqualTo(plan.sha256());assertThat(grant.verificationId()).isEqualTo(report.verificationId());admission.recheck(plan,grant);verify(state,times(4)).read(eq(plan),any());
        when(state.read(eq(plan),any())).thenReturn("a".repeat(64),"b".repeat(64));assertThatThrownBy(()->admission.verify(plan)).isInstanceOf(IllegalStateException.class);
        when(state.read(eq(plan),any())).thenReturn(report.stateSha256());save(plan,report(plan,report.stateSha256()),key);assertThatThrownBy(()->admission.recheck(plan,grant)).isInstanceOf(IllegalStateException.class);
    }
    class Server implements AutoCloseable {
        final HttpsServer server;final ExecutorService executor=Executors.newFixedThreadPool(2);final String ca;
        final List<String> requests=new CopyOnWriteArrayList<>();final Map<String,JsonNode> reads=new ConcurrentHashMap<>();
        final Map<String,String> raw=new ConcurrentHashMap<>();volatile int status=200,missingStatus=200;
        Server() throws Exception {
            var cert=new HeldCertificate.Builder().commonName("runtime-fixture").addSubjectAlternativeName("127.0.0.1").certificateAuthority(0).build();ca=cert.certificatePem();
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslContext()));server.setExecutor(executor);
            server.createContext("/",exchange->{String path=exchange.getRequestURI().toString();requests.add(exchange.getRequestMethod()+" "+path);int code=status==200 && !reads.containsKey(path) && !raw.containsKey(path)?missingStatus:status;if(code==302)exchange.getResponseHeaders().set("Location",origin()+"/trap");byte[] bytes=(code==200?raw.getOrDefault(path,Objects.toString(reads.get(path),"{}")):"{\"message\":\"private-value\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        }
        String origin(){return "https://127.0.0.1:"+server.getAddress().getPort();}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
    record Bound(ExecutionClusterClients clients,ExecutionClusterClients.Binding binding,K6PlanEnvelope.Prepared plan,ExecutionClusterClients.Scope scope){}
    Bound bound(Server server,List<String> operations) throws Exception {
        var plan=plan();var body=(ObjectNode)json.readTree(plan.canonicalJson());UUID target=UUID.fromString(body.path("clusterBinding").path("targetId").asText());String ns=body.path("policy").path("namespace").asText();
        var store=mock(ConnectionStore.class);when(store.view(target)).thenReturn(new ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));when(store.kubernetesMaterial(target)).thenReturn(new ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token"));
        var clients=new ExecutionClusterClients(json.writeValueAsString(List.of(Map.of("targetId",target.toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace",ns,"operations",operations))),json,store);var binding=clients.binding(target,ns);body.set("clusterBinding",json.valueToTree(binding));plan=prepared(body);server.reads.putAll(reads(plan));
        return new Bound(clients,binding,plan,new ExecutionClusterClients.Scope(target,1,ns,body.path("workerName").asText(),null,ExecutionClusterClients.Access.RUNTIME));
    }
    @Test void realScopedTlsReadsAdmitSignedEvidenceAndDenyUnrelatedPathsWritesAndRedirects() throws Exception {
        try(var server=new Server()){
            var b=bound(server,List.of("RUNTIME"));var report=report(b.plan(),digest(b.plan(),server.reads));save(b.plan(),report,key);
            var state=new KubernetesRuntimeState(b.clients(),json,"network-system","cni-agent");var admission=new SignedRuntimeAdmission(evidence(),state,Clock.fixed(now,ZoneOffset.UTC));
            var grant=admission.verify(b.plan());assertThat(grant.planSha256()).isEqualTo(b.plan().sha256());assertThat(server.requests).hasSize(14).allMatch(s->s.startsWith("GET "));
            assertThatThrownBy(()->b.clients().openBound(b.scope(),b.binding())).isInstanceOf(ExecutionClusterClients.Problem.class);
            assertThatThrownBy(()->b.clients().openRuntimeRead(b.scope(),b.binding(),b.plan(),"network-system","cni-agent","/api/v1/secrets")).isInstanceOf(ExecutionClusterClients.Problem.class);
            var foreign=(ObjectNode)json.readTree(b.plan().canonicalJson());((ObjectNode)foreign.path("policy")).put("namespace","foreign");
            assertThatThrownBy(()->b.clients().openRuntimeRead(b.scope(),b.binding(),prepared(foreign),"network-system","cni-agent","/api/v1/namespaces/foreign")).isInstanceOf(ExecutionClusterClients.Problem.class);
            try(var session=b.clients().openRuntimeRead(b.scope(),b.binding(),b.plan(),"network-system","cni-agent","/api/v1/nodes?limit=33")){
                for(String url:List.of("/api/v1/nodes?limit=99","/api/v1/nodes?limit=33&continue=extra","/api/v1/secrets"))assertThatThrownBy(()->session.client().getHttpClient().newCall(new Request.Builder().url(server.origin()+url).build()).execute()).isInstanceOf(java.io.IOException.class);
                assertThatThrownBy(()->session.client().getHttpClient().newCall(new Request.Builder().url(server.origin()+"/api/v1/nodes?limit=33").delete().build()).execute()).isInstanceOf(java.io.IOException.class);
            }
            assertThat(server.requests).hasSize(14);
            for(int status:List.of(302,403,500)){server.status=status;assertThatThrownBy(()->admission.recheck(b.plan(),grant)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-value");}
            assertThat(server.requests).noneMatch(path->path.contains("trap"));
        }
    }
    @Test void inspectionAndLegacyWorkerGrantsNeverInheritRuntimeReadAuthority() throws Exception {
        try(var server=new Server()){
            var b=bound(server,List.of("READ","STOP","PREPARE","METRICS","ACTIVATE","RECOVER"));
            assertThatThrownBy(()->b.clients().openRuntimeRead(b.scope(),b.binding(),b.plan(),"network-system","cni-agent","/api/v1/nodes?limit=33")).isInstanceOf(ExecutionClusterClients.Problem.class);assertThat(server.requests).isEmpty();
        }
    }
}
