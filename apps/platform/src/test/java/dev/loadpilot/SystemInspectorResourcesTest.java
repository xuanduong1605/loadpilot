package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import dev.loadpilot.integrations.*;
import dev.loadpilot.targets.*;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.tls.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemInspectorResourcesTest {
    @Test void resourcePollingReadsKubernetesOnlyWithoutProbingTheApplicationOrMonitoring() throws Exception {
        var cert=new HeldCertificate.Builder().commonName("resource-fixture").addSubjectAlternativeName("127.0.0.1").certificateAuthority(0).build();
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newFixedThreadPool(2);var requests=new CopyOnWriteArrayList<String>();
        server.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslContext()));server.setExecutor(executor);
        var owner=new V1OwnerReference().apiVersion("apps/v1").kind("Deployment").name("api").uid("deployment-uid").controller(true);
        var deployment=new V1Deployment().apiVersion("apps/v1").kind("Deployment").metadata(new V1ObjectMeta().name("api").namespace("fixture").uid("deployment-uid").generation(1L))
                .spec(new V1DeploymentSpec().replicas(1).selector(new V1LabelSelector().matchLabels(Map.of("app","api"))).template(new V1PodTemplateSpec().spec(new V1PodSpec().containers(List.of(new V1Container().name("api").image("fixture.invalid/api"))))));
        var sets=new V1ReplicaSetList().apiVersion("apps/v1").kind("ReplicaSetList").items(List.of(new V1ReplicaSet().metadata(new V1ObjectMeta().namespace("fixture").name("api-rs").uid("rs-uid").ownerReferences(List.of(owner)))));
        var podOwner=new V1OwnerReference().apiVersion("apps/v1").kind("ReplicaSet").name("api-rs").uid("rs-uid").controller(true);
        var pods=new V1PodList().apiVersion("v1").kind("PodList").items(List.of(new V1Pod().metadata(new V1ObjectMeta().namespace("fixture").name("api-pod").uid("pod-uid").ownerReferences(List.of(podOwner)))
                .spec(new V1PodSpec().containers(List.of(new V1Container().name("api").image("fixture.invalid/api")))).status(new V1PodStatus().phase("Running").containerStatuses(List.of(new V1ContainerStatus().name("api").image("fixture.invalid/api").imageID("fixture").ready(true).restartCount(0))))));
        String samples="{\"items\":[{\"metadata\":{\"namespace\":\"fixture\",\"name\":\"api-pod\"},\"timestamp\":\""+Instant.now()+"\",\"window\":\"30s\",\"containers\":[{\"name\":\"api\",\"usage\":{\"cpu\":\"50m\",\"memory\":\"64Mi\"}}]}]}";
        var responses=Map.of("/apis/apps/v1/namespaces/fixture/deployments/api",JSON.serialize(deployment),"/apis/apps/v1/namespaces/fixture/replicasets",JSON.serialize(sets),"/api/v1/namespaces/fixture/pods",JSON.serialize(pods),"/apis/metrics.k8s.io/v1beta1/namespaces/fixture/pods",samples);
        server.createContext("/",exchange->{
            requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());
            byte[] response=responses.getOrDefault(exchange.getRequestURI().getPath(),"{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var id=UUID.randomUUID();String origin="https://127.0.0.1:"+server.getAddress().getPort();var targets=mock(TargetStore.class);var connections=mock(ConnectionStore.class);
            when(targets.get(id)).thenReturn(new Target(id,"fixture",origin+"/must-not-probe","STAGING","fixture","api","api",null,null,1,Instant.now(),Instant.now(),null));
            when(connections.kubernetesMaterial(id)).thenReturn(new ConnectionStore.KubernetesMaterial(1,origin,cert.certificatePem(),"fixture-token"));
            var inspector=new SystemInspector(targets,connections,new EndpointPolicy(origin,"127.0.0.1"),new ObjectMapper());
            var result=inspector.resources(id);assertThat(result.metrics().state()).isEqualTo("READY");assertThat(result.pods()).hasSize(1);assertThat(result.pods().getFirst().usage().cpuMillicores()).isEqualByComparingTo("50");
            assertThat(result.pods().getFirst().usage().memoryBytes()).isEqualByComparingTo("67108864");assertThat(result.api().state()).isEqualTo("NOT_COLLECTED");assertThat(result.prometheus().state()).isEqualTo("NOT_COLLECTED");
            verify(connections,never()).material(id);
            assertThat(requests).containsExactly("GET /apis/apps/v1/namespaces/fixture/deployments/api","GET /apis/apps/v1/namespaces/fixture/replicasets","GET /api/v1/namespaces/fixture/pods","GET /apis/metrics.k8s.io/v1beta1/namespaces/fixture/pods");
        } finally {server.stop(0);executor.shutdownNow();}
    }
}
