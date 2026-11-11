package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import dev.loadpilot.integrations.k6.*;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.models.*;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.tls.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KubernetesWorkerActivatorTest {
    final ObjectMapper json=new ObjectMapper();
    final KubernetesWorkerActivator.Resources uids=new KubernetesWorkerActivator.Resources("files-uid","network-uid","job-uid");
    class Fixture implements AutoCloseable {
        final HttpsServer server;final ExecutorService executor=Executors.newFixedThreadPool(2);final String ca;
        final List<String> requests=new CopyOnWriteArrayList<>(),bodies=new CopyOnWriteArrayList<>(),types=new CopyOnWriteArrayList<>(),authorization=new CopyOnWriteArrayList<>();
        K6PlanEnvelope.Prepared plan;V1ConfigMap files;V1NetworkPolicy network;V1Job job;volatile int patchStatus=200;volatile boolean deleted,alterAcknowledgement;
        volatile int createStatus=200;volatile boolean fresh,podMissing,podLingersAfterDelete;volatile V1Pod overridePod;V1Pod cachedPod;
        volatile String metricLogOverride;
        volatile boolean legacyTemplateTimestamp,unknownJobField;
        volatile int cleanupVersionConflicts,cleanupDeleteError;
        final Set<String> created=ConcurrentHashMap.newKeySet();
        final Set<String> removed=ConcurrentHashMap.newKeySet();
        Fixture() throws Exception {
            plan=new KubernetesWorkerPreparerTest().plan().plan();var body=json.readTree(plan.canonicalJson());
            files=JSON.deserialize(body.path("files").toString(),V1ConfigMap.class);network=JSON.deserialize(body.path("network").toString(),V1NetworkPolicy.class);job=JSON.deserialize(body.path("job").toString(),V1Job.class);
            files.getMetadata().uid(uids.filesUid()).resourceVersion("1");network.getMetadata().uid(uids.networkUid()).resourceVersion("1");job.getMetadata().uid(uids.jobUid()).resourceVersion("10");
            network.getSpec().setIngress(null); // Kubernetes omits empty rule arrays from its response.
            var cert=new HeldCertificate.Builder().commonName("activation-fixture").addSubjectAlternativeName("127.0.0.1").certificateAuthority(0).build();ca=cert.certificatePem();
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslContext()));server.setExecutor(executor);
            server.createContext("/",exchange->{
                requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);bodies.add(input);types.add(Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"),""));
                authorization.add(Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),""));
                int status=200;String response;String path=exchange.getRequestURI().getPath();
                if(exchange.getRequestMethod().equals("POST")) {
                    status=createStatus;
                    String resource=path.endsWith("/configmaps")?"files":path.endsWith("/networkpolicies")?"network":"job";
                    if(status==200 && !created.add(resource))status=409;
                    response=status==200?resource.equals("files")?JSON.serialize(files):resource.equals("network")?JSON.serialize(network):JSON.serialize(job):"{\"message\":\"private upstream detail\"}";
                } else if(exchange.getRequestMethod().equals("PATCH")) {
                    status=patchStatus;
                    if(status==302) exchange.getResponseHeaders().set("Location",origin()+"/trap");
                    if(status==200) {
                        var patch=json.readTree(input);
                        if(!patch.isArray() || !job.getMetadata().getUid().equals(patch.get(0).path("value").asText()) || !job.getMetadata().getResourceVersion().equals(patch.get(1).path("value").asText()) || !patch.get(2).path("value").equals(apiJob().path("spec")) || !Boolean.TRUE.equals(job.getSpec().getSuspend())) status=409;
                        else {job.getSpec().suspend(false);job.getMetadata().resourceVersion("11");if(alterAcknowledgement) job.getSpec().getTemplate().getSpec().getContainers().getFirst().image("unreviewed.invalid/k6");}
                    }
                    response=status==200?apiJob().toString():"{\"message\":\"private upstream detail\"}";
                } else if(exchange.getRequestMethod().equals("DELETE")) {
                    var options=JSON.<V1DeleteOptions>deserialize(input,V1DeleteOptions.class);
                    String resource=path.contains("/configmaps/")?"files":path.contains("/networkpolicies/")?"network":"job";
                    var meta=resource.equals("files")?files.getMetadata():resource.equals("network")?network.getMetadata():job.getMetadata();
                    if(options.getPreconditions()==null || !meta.getUid().equals(options.getPreconditions().getUid()) || (options.getPreconditions().getResourceVersion()!=null && !meta.getResourceVersion().equals(options.getPreconditions().getResourceVersion()))) {status=409;response="{}";}
                    else if(resource.equals("job") && options.getPreconditions().getResourceVersion()!=null && cleanupVersionConflicts>0) {
                        cleanupVersionConflicts--;
                        meta.resourceVersion(Integer.toString(Integer.parseInt(meta.getResourceVersion())+1));
                        status=409;response="{}";
                    }
                    else if(resource.equals("job") && options.getPreconditions().getResourceVersion()!=null && cleanupDeleteError!=0) {status=cleanupDeleteError;response="{}";}
                    else {removed.add(resource);if(resource.equals("job"))deleted=true;response="{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Success\",\"code\":200}";}
                } else if(path.endsWith("/log")) {
                    var samples=new K6LiveHttpMetricsTest();var at=java.time.Instant.now();
                    try{response=metricLogOverride!=null?metricLogOverride:(samples.request(at.minusSeconds(6),100,"200","0")+samples.request(at.minusSeconds(5),300,"500","0")).replace(samples.now.minusSeconds(1).toString(),at.minusSeconds(1).toString());}catch(Exception invalid){throw new java.io.IOException("Fixture sample encoding failed");}
                } else if(path.contains("/pods/")) response=JSON.serialize(selectedPod());
                else if(path.endsWith("/pods")) {
                    boolean absent=podMissing || (deleted && !podLingersAfterDelete) || Boolean.TRUE.equals(job.getSpec().getSuspend());
                    response=JSON.serialize(new V1PodList().metadata(new V1ListMeta().resourceVersion("1")).items(absent?List.of():List.of(selectedPod())));
                }
                else if(deleted && path.contains("/jobs/")) {status=404;response="{}";}
                else if(removed.contains(path.contains("/configmaps/")?"files":path.contains("/networkpolicies/")?"network":"job")){status=404;response="{}";}
                else if(fresh && !created.contains(path.contains("/configmaps/")?"files":path.contains("/networkpolicies/")?"network":"job")){status=404;response="{}";}
                else response=path.contains("/configmaps/")?JSON.serialize(files):path.contains("/networkpolicies/")?JSON.serialize(network):apiJob().toString();
                var bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
        }
        private ObjectNode apiJob() throws java.io.IOException {
            var body=(ObjectNode)json.readTree(JSON.serialize(job));
            if(legacyTemplateTimestamp)((ObjectNode)body.at("/spec/template/metadata")).putNull("creationTimestamp");
            if(unknownJobField)((ObjectNode)body.at("/spec/template/spec")).put("foreignUnsafeField",true);
            return body;
        }
        String origin(){return "https://127.0.0.1:"+server.getAddress().getPort();}
        synchronized V1Pod selectedPod(){if(overridePod!=null)return overridePod;if(cachedPod==null)cachedPod=new KubernetesWorkerLogsTest().pod(job);return cachedPod;}
        void configure(K6PlanEnvelope.Prepared exact) throws Exception {
            cachedPod=null;
            plan=exact;var body=json.readTree(exact.canonicalJson());files=JSON.deserialize(body.path("files").toString(),V1ConfigMap.class);network=JSON.deserialize(body.path("network").toString(),V1NetworkPolicy.class);job=JSON.deserialize(body.path("job").toString(),V1Job.class);
            files.getMetadata().uid(uids.filesUid()).resourceVersion("1");network.getMetadata().uid(uids.networkUid()).resourceVersion("1");job.getMetadata().uid(uids.jobUid()).resourceVersion("10");
            network.getSpec().setIngress(null);
        }
        ApiClient client(){return new ApiClient().setBasePath(origin()).setSslCaCert(new ByteArrayInputStream(ca.getBytes(StandardCharsets.UTF_8)));}
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
    @Test void inspectsResourcesAndUsesOneConditionalPatchWithoutClaimingWorkerRunning() throws Exception {
        try(var server=new Fixture();var activator=new KubernetesWorkerActivator(server.client(),json)) {
            server.legacyTemplateTimestamp=true;
            server.job.getSpec().completionMode("NonIndexed").manualSelector(false).selector(new V1LabelSelector().matchLabels(Map.of("batch.kubernetes.io/controller-uid","job-uid")));
            server.job.getSpec().getTemplate().getMetadata().getLabels().put("batch.kubernetes.io/job-name",server.job.getMetadata().getName());
            server.job.getSpec().getTemplate().getSpec().schedulerName("default-scheduler").serviceAccount("loadpilot-runner");
            var command=activator.prepare(server.plan,uids);assertThat(server.requests).hasSize(3).allMatch(value->value.startsWith("GET "));
            assertThat(command.patchHash()).isEqualTo(K6PlanEnvelope.hash(command.patchJson()));var patch=json.readTree(command.patchJson());
            assertThat(patch.get(0).path("path").asText()).isEqualTo("/metadata/uid");assertThat(patch.get(1).path("path").asText()).isEqualTo("/metadata/resourceVersion");assertThat(patch.get(2).path("path").asText()).isEqualTo("/spec");
            assertThat(patch.get(2).at("/value/template/metadata/creationTimestamp").isNull()).isTrue();
            assertThat(activator.activate(command,UUID.randomUUID())).isEqualTo(new KubernetesWorkerActivator.Receipt("job-uid","11"));
            assertThat(server.requests).hasSize(4);assertThat(server.types.getLast()).startsWith("application/json-patch+json");assertThat(server.job.getSpec().getSuspend()).isFalse();
        }
    }
    @Test void resourceOrSecurityDriftRefusesActivationBeforePatch() throws Exception {
        for(String change:List.of("source","network","ingress","policy-types","image","privilege","uid","already-active","summary-path","summary-policy","pod-deadline","unknown-job-field")) try(var server=new Fixture();var activator=new KubernetesWorkerActivator(server.client(),json)) {
            switch(change) {
                case "source"->server.files.getData().put("scenario.js","changed");
                case "network"->server.network.getSpec().setEgress(List.of());
                case "ingress"->server.network.getSpec().setIngress(List.of(new V1NetworkPolicyIngressRule()));
                case "policy-types"->server.network.getSpec().setPolicyTypes(List.of("Ingress"));
                case "image"->server.job.getSpec().getTemplate().getSpec().getContainers().getFirst().image("unreviewed.invalid/k6");
                case "privilege"->server.job.getSpec().getTemplate().getSpec().getContainers().getFirst().getSecurityContext().privileged(true);
                case "uid"->server.job.getMetadata().uid("replacement-job");
                case "already-active"->server.job.getSpec().suspend(false);
                case "summary-path"->server.job.getSpec().getTemplate().getSpec().getContainers().getFirst().terminationMessagePath("/dev/termination-log");
                case "summary-policy"->server.job.getSpec().getTemplate().getSpec().getContainers().getFirst().terminationMessagePolicy("FallbackToLogsOnError");
                case "pod-deadline"->server.job.getSpec().getTemplate().getSpec().activeDeadlineSeconds(null);
                case "unknown-job-field"->server.unknownJobField=true;
            }
            assertThatThrownBy(()->activator.prepare(server.plan,uids)).as(change).isInstanceOf(KubernetesWorkerActivator.Problem.class);assertThat(server.requests).noneMatch(value->value.startsWith("PATCH "));
        }
    }
    @Test void revisionConflictPermissionRedirectAndUncertaintyNeverRetryOrExposePrivateBody() throws Exception {
        for(int status:List.of(409,422,403,302,500)) try(var server=new Fixture();var activator=new KubernetesWorkerActivator(server.client(),json)) {
            var command=activator.prepare(server.plan,uids);server.patchStatus=status;server.requests.clear();
            assertThatThrownBy(()->activator.activate(command,UUID.randomUUID())).isInstanceOf(KubernetesWorkerActivator.Problem.class).hasMessageNotContaining("private upstream");assertThat(server.requests).hasSize(1).noneMatch(value->value.contains("/trap"));
        }
    }
    @Test void staleRevisionAndMutatedAcknowledgementDoNotBecomeActivationSuccess() throws Exception {
        for(boolean stale:List.of(true,false)) try(var server=new Fixture();var activator=new KubernetesWorkerActivator(server.client(),json)) {
            var command=activator.prepare(server.plan,uids);server.requests.clear();if(stale) server.job.getMetadata().resourceVersion("changed");else server.alterAcknowledgement=true;
            assertThatThrownBy(()->activator.activate(command,UUID.randomUUID())).isInstanceOfSatisfying(KubernetesWorkerActivator.Problem.class,error->assertThat(error.status()).isEqualTo(stale?409:503));assertThat(server.requests).hasSize(1);
        }
    }
    @Test void activationScopesDenyGenericAccessOtherResourcesAndChangedPatchBeforeTransmission() throws Exception {
        try(var server=new Fixture()) {
            var id=UUID.randomUUID();var store=org.mockito.Mockito.mock(dev.loadpilot.integrations.ConnectionStore.class);
            org.mockito.Mockito.when(store.view(id)).thenReturn(new dev.loadpilot.integrations.ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));
            org.mockito.Mockito.when(store.kubernetesMaterial(id)).thenReturn(new dev.loadpilot.integrations.ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token"));
            String ns=server.job.getMetadata().getNamespace(),name=server.job.getMetadata().getName();String config=json.writeValueAsString(List.of(Map.of("targetId",id.toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace",ns,"operations",List.of("ACTIVATE"))));
            var provider=new ExecutionClusterClients(config,json,store);var binding=provider.binding(id,ns);var scope=new ExecutionClusterClients.Scope(id,1,ns,name,"job-uid",ExecutionClusterClients.Access.ACTIVATE);
            assertThatThrownBy(()->provider.open(scope)).isInstanceOf(ExecutionClusterClients.Problem.class);org.mockito.Mockito.verify(store,org.mockito.Mockito.never()).kubernetesMaterial(id);
            KubernetesWorkerActivator.Prepared command;
            try(var session=provider.openActivationRead(scope,binding);var activator=new KubernetesWorkerActivator(session.client(),json)) {
                assertThatThrownBy(()->new io.kubernetes.client.openapi.apis.CoreV1Api(session.client()).readNamespacedConfigMap("other",ns).execute()).isInstanceOf(ApiException.class);assertThat(server.requests).isEmpty();
                command=activator.prepare(server.plan,uids);
                assertThatThrownBy(()->activator.activate(command,UUID.randomUUID())).isInstanceOf(KubernetesWorkerActivator.Problem.class);assertThat(server.requests).hasSize(3);
            }
            try(var session=provider.openActivationPatch(scope,binding,command);var activator=new KubernetesWorkerActivator(session.client(),json)) {
                assertThatThrownBy(()->new io.kubernetes.client.openapi.apis.BatchV1Api(session.client()).readNamespacedJob(name,ns).execute()).isInstanceOf(ApiException.class);
                var body=command.patchJson().replace("/spec/suspend","/spec/parallelism");
                var attack=new okhttp3.Request.Builder().url(server.origin()+"/apis/batch/v1/namespaces/"+ns+"/jobs/"+name).patch(okhttp3.RequestBody.create(body,okhttp3.MediaType.get("application/json-patch+json"))).build();
                assertThatThrownBy(()->session.client().getHttpClient().newCall(attack).execute()).isInstanceOf(java.io.IOException.class);assertThat(server.requests).hasSize(3);
                assertThat(activator.activate(command,UUID.randomUUID()).jobUid()).isEqualTo("job-uid");assertThat(server.requests).hasSize(4);assertThat(server.authorization).containsExactly("Bearer fixture-token","Bearer fixture-token","Bearer fixture-token","Bearer fixture-token");
            }
        }
    }
}
