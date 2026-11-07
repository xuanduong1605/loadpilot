package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.ConnectionStore;
import dev.loadpilot.integrations.k6.*;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionClusterClientsTest {
    final UUID target=UUID.randomUUID();final ObjectMapper json=new ObjectMapper();
    String config(String origin,List<String> operations) throws Exception {return json.writeValueAsString(List.of(Map.of("targetId",target.toString(),"connectionRevision",1,"origin",origin,"addresses",List.of("127.0.0.1"),"namespace","runners","operations",operations)));}
    ExecutionClusterClients.Scope scope(String worker,ExecutionClusterClients.Access access,String uid){return new ExecutionClusterClients.Scope(target,1,"runners",worker,uid,access);}
    record Preparation(ExecutionClusterClients provider,ExecutionClusterClients.Binding binding,ExecutionClusterClients.Scope scope,K6PlanEnvelope.Prepared plan,UUID submit) {}
    Preparation preparation(KubernetesWorkerPreparerTest.Fixture server,List<String> operations,ConnectionStore store) throws Exception {
        var owner=new K6JobPlanTest();var fixture=owner.fixture(owner.steady(),"export default function() {}\n");var id=fixture.snapshot().inputs().target().id();
        when(store.view(id)).thenReturn(new ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));
        when(store.kubernetesMaterial(id)).thenReturn(new ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token"));
        String config=json.writeValueAsString(List.of(Map.of("targetId",id.toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace",owner.policy.namespace(),"operations",operations)));
        var provider=new ExecutionClusterClients(config,json,store);var binding=provider.binding(id,owner.policy.namespace());
        var plan=new K6PlanEnvelope(json,jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator()).prepare(fixture.snapshot(),fixture.ticket(),owner.policy,owner.destination,binding,owner.now);
        return new Preparation(provider,binding,new ExecutionClusterClients.Scope(id,1,owner.policy.namespace(),fixture.ticket().workerName(),null,ExecutionClusterClients.Access.PREPARE),plan,fixture.ticket().submitOperationId());
    }
    @Test void preparationGrantCreatesOnlyExactReviewedSuspendedResourcesWithStoredCredential() throws Exception {
        var data=new KubernetesWorkerPreparerTest();var store=mock(ConnectionStore.class);
        try(var server=data.new Fixture()) {
            var p=preparation(server,List.of("PREPARE","READ","STOP"),store);
            for(var resource:KubernetesWorkerPreparer.Resource.values()) try(var session=p.provider().openPreparation(p.scope(),p.binding(),p.plan(),resource);var adapter=new KubernetesWorkerPreparer(session.client(),json)) {
                assertThat(adapter.create(p.plan(),p.submit(),resource).resource()).isEqualTo(resource);
            }
            assertThat(server.requests).hasSize(3).allMatch(value->value.startsWith("POST "));
            assertThat(server.authorization).containsExactly("Bearer fixture-token","Bearer fixture-token","Bearer fixture-token");
            assertThat(json.readTree(server.bodies.get(2)).path("spec").path("suspend").asBoolean()).isTrue();
        }
    }
    @Test void preparationRejectsChangedBodyMethodPathAndActivationBeforeTransmission() throws Exception {
        var data=new KubernetesWorkerPreparerTest();var store=mock(ConnectionStore.class);
        try(var server=data.new Fixture()) {
            var p=preparation(server,List.of("PREPARE"),store);var envelope=json.readTree(p.plan().canonicalJson());
            for(var resource:KubernetesWorkerPreparer.Resource.values()) try(var session=p.provider().openPreparation(p.scope(),p.binding(),p.plan(),resource)) {
                String field=switch(resource) {case FILES->"files";case NETWORK->"network";case JOB->"job";};
                String path=switch(resource) {case FILES->"/api/v1/namespaces/"+p.scope().namespace()+"/configmaps";case NETWORK->"/apis/networking.k8s.io/v1/namespaces/"+p.scope().namespace()+"/networkpolicies";case JOB->"/apis/batch/v1/namespaces/"+p.scope().namespace()+"/jobs";};
                var changed=envelope.path(field).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("unreviewed",true);
                for(String body:List.of(changed.toString(),envelope.path(field)+" {}","{\"kind\":\"Other\","+envelope.path(field).toString().substring(1))) {
                    var request=new okhttp3.Request.Builder().url(server.origin()+path).post(okhttp3.RequestBody.create(body,okhttp3.MediaType.get("application/json"))).build();
                    assertThatThrownBy(()->session.client().getHttpClient().newCall(request).execute()).isInstanceOf(java.io.IOException.class);
                }
                var get=new okhttp3.Request.Builder().url(server.origin()+path).get().build();
                assertThatThrownBy(()->session.client().getHttpClient().newCall(get).execute()).isInstanceOf(java.io.IOException.class);
                var alternate=new okhttp3.Request.Builder().url(server.origin()+path+"?dryRun=All").post(okhttp3.RequestBody.create(envelope.path(field).toString(),okhttp3.MediaType.get("application/json"))).build();
                assertThatThrownBy(()->session.client().getHttpClient().newCall(alternate).execute()).isInstanceOf(java.io.IOException.class);
                if(resource==KubernetesWorkerPreparer.Resource.JOB) {
                    var active=envelope.path(field).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)active.path("spec")).put("suspend",false);
                    var request=new okhttp3.Request.Builder().url(server.origin()+path).post(okhttp3.RequestBody.create(active.toString(),okhttp3.MediaType.get("application/json"))).build();
                    assertThatThrownBy(()->session.client().getHttpClient().newCall(request).execute()).isInstanceOf(java.io.IOException.class);
                }
            }
            assertThat(server.requests).isEmpty();assertThat(server.authorization).isEmpty();
        }
    }
    @Test void preparationNeedsExplicitGrantAndPlanPermissionBeforeCredentialAccess() throws Exception {
        var data=new KubernetesWorkerPreparerTest();var store=mock(ConnectionStore.class);
        try(var server=data.new Fixture()) {
            var p=preparation(server,List.of("READ"),store);
            assertThatThrownBy(()->p.provider().openPreparation(p.scope(),p.binding(),p.plan(),KubernetesWorkerPreparer.Resource.FILES)).isInstanceOf(ExecutionClusterClients.Problem.class);
            verify(store,never()).kubernetesMaterial(org.mockito.ArgumentMatchers.any());
            var allowed=preparation(server,List.of("PREPARE"),store);clearInvocations(store);
            assertThatThrownBy(()->allowed.provider().open(allowed.scope())).isInstanceOf(ExecutionClusterClients.Problem.class);
            assertThatThrownBy(()->allowed.provider().openPreparation(allowed.scope(),allowed.binding(),new K6PlanEnvelope.Prepared(allowed.plan().canonicalJson(),"0".repeat(64)),KubernetesWorkerPreparer.Resource.FILES)).isInstanceOf(ExecutionClusterClients.Problem.class);
            verify(store,never()).kubernetesMaterial(org.mockito.ArgumentMatchers.any());assertThat(server.requests).isEmpty();
        }
    }
    @Test void disabledAndDeniedGrantsNeverDecryptStoredCredentials() throws Exception {
        var store=mock(ConnectionStore.class);var disabled=new ExecutionClusterClients("",json,store);
        assertThatThrownBy(()->disabled.open(scope("lp-fixture",ExecutionClusterClients.Access.READ,null))).isInstanceOfSatisfying(ExecutionClusterClients.Problem.class,error->assertThat(error.status()).isEqualTo(503));
        var readOnly=new ExecutionClusterClients(config("https://127.0.0.1:6443",List.of("READ")),json,store);
        assertThatThrownBy(()->readOnly.open(scope("lp-fixture",ExecutionClusterClients.Access.STOP,"uid"))).isInstanceOf(ExecutionClusterClients.Problem.class);
        assertThatThrownBy(()->readOnly.open(new ExecutionClusterClients.Scope(target,2,"runners","lp-fixture",null,ExecutionClusterClients.Access.READ))).isInstanceOf(ExecutionClusterClients.Problem.class);
        verifyNoInteractions(store);
    }
    @Test void readsOnlySavedWorkerAndBoundedPodsWithStoredTlsCredential() throws Exception {
        var data=new KubernetesWorkerCollectorTest();
        try(var fixture=data.new Fixture()) {
            var store=mock(ConnectionStore.class);when(store.kubernetesMaterial(target)).thenReturn(new ConnectionStore.KubernetesMaterial(1,fixture.origin(),fixture.ca,"fixture-token"));
            var provider=new ExecutionClusterClients(config(fixture.origin(),List.of("READ")),json,store);
            try(var session=provider.open(scope(data.ticket.workerName(),ExecutionClusterClients.Access.READ,null));var collector=new KubernetesWorkerCollector(session.client())) {
                assertThat(collector.collect(data.ticket,data.job(),"fixture-job").allWorkersTerminated()).isTrue();
                assertThat(session.toString()).doesNotContain("fixture-token");assertThat(session.connectionRevision()).isEqualTo(1);
                assertThatThrownBy(()->new BatchV1Api(session.client()).readNamespacedJob(data.ticket.workerName(),"elsewhere").execute()).isInstanceOf(Exception.class);
                assertThat(fixture.requests).hasSize(3);
            }
        }
    }
    @Test void stopScopeChecksExactUidBeforeAnyDeleteAndRejectsCredentialRevisionDrift() throws Exception {
        var data=new KubernetesWorkerStopperTest();
        try(var fixture=data.new Fixture()) {
            var store=mock(ConnectionStore.class);when(store.kubernetesMaterial(target)).thenReturn(new ConnectionStore.KubernetesMaterial(1,fixture.origin(),fixture.ca,"fixture-token"));
            var provider=new ExecutionClusterClients(config(fixture.origin(),List.of("STOP")),json,store);
            try(var session=provider.open(scope("lp-fixture",ExecutionClusterClients.Access.STOP,"fixture-uid"));var stopper=new KubernetesWorkerStopper(session.client())) {
                assertThatThrownBy(()->stopper.request(new KubernetesWorkerStopper.Command("runners","lp-fixture","wrong-uid",UUID.randomUUID()))).isInstanceOf(KubernetesWorkerStopper.Problem.class);
                assertThat(fixture.requests).isEmpty();
                assertThat(stopper.request(data.command)).isEqualTo(KubernetesWorkerStopper.Acknowledgement.ACCEPTED);assertThat(fixture.requests).hasSize(1);
            }
            when(store.kubernetesMaterial(target)).thenReturn(new ConnectionStore.KubernetesMaterial(2,fixture.origin(),fixture.ca,"replacement-fixture"));
            assertThatThrownBy(()->provider.open(scope("lp-fixture",ExecutionClusterClients.Access.STOP,"fixture-uid"))).isInstanceOfSatisfying(ExecutionClusterClients.Problem.class,error->assertThat(error.status()).isEqualTo(409));
        }
    }
    @Test void strictGrantSchemaRejectsUnsafeOrAmbiguousConfiguration() throws Exception {
        var store=mock(ConnectionStore.class);String valid=config("https://127.0.0.1:6443",List.of("READ"));
        for(String value:List.of("{}",valid+" []",valid.replace("\"READ\"","\"ADMIN\""),valid.replace("127.0.0.1\"]","169.254.169.254\"]"),valid.replace("\"runners\"","\"kube-system\""),valid.replace("https://127.0.0.1:6443","https://user:private-value@127.0.0.1:6443")))
            assertThatThrownBy(()->new ExecutionClusterClients(value,json,store)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-value");
        assertThat(new ConnectionStore.KubernetesMaterial(1,"url","ca","private-value").toString()).doesNotContain("private-value");verifyNoInteractions(store);
    }
    @Test void bindingUsesOnlyMetadataAndRejectsChangedRevisionBeforeCredentialAccess() throws Exception {
        var data=new KubernetesWorkerCollectorTest();
        try(var fixture=data.new Fixture()) {
            var store=mock(ConnectionStore.class);
            when(store.view(target)).thenReturn(new ConnectionStore.View(1,fixture.origin(),fixture.ca,true,"","",false,null));
            when(store.kubernetesMaterial(target)).thenReturn(new ConnectionStore.KubernetesMaterial(1,fixture.origin(),fixture.ca,"fixture-token"));
            var provider=new ExecutionClusterClients(config(fixture.origin(),List.of("READ")),json,store);var binding=provider.binding(target,"runners");
            verify(store,never()).kubernetesMaterial(target);assertThat(binding.caSha256()).hasSize(64);assertThat(binding.toString()).doesNotContain("BEGIN CERTIFICATE");
            try(var session=provider.openBound(scope(data.ticket.workerName(),ExecutionClusterClients.Access.READ,null),binding)) {assertThat(session.connectionRevision()).isEqualTo(1);}
            clearInvocations(store);when(store.view(target)).thenReturn(new ConnectionStore.View(2,fixture.origin(),fixture.ca,true,"","",false,null));
            assertThatThrownBy(()->provider.openBound(scope(data.ticket.workerName(),ExecutionClusterClients.Access.READ,null),binding)).isInstanceOf(ExecutionClusterClients.Problem.class);
            verify(store,never()).kubernetesMaterial(target);
        }
    }
}
