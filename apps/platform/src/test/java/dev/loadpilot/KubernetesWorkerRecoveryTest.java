package dev.loadpilot;

import com.fasterxml.jackson.databind.*;
import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.*;
import dev.loadpilot.integrations.k6.*;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesWorkerRecoveryTest {
 final ObjectMapper json=new ObjectMapper();
 class Fixture implements AutoCloseable {
  final KubernetesWorkerActivatorTest.Fixture server=new KubernetesWorkerActivatorTest().new Fixture();
  final ExecutionClusterClients clients;final ExecutionClusterClients.Binding binding;final K6PlanEnvelope.Prepared plan;final ExecutionClusterClients.Scope scope;
  final UUID target;
  Fixture() throws Exception {
   var body=json.readTree(server.plan.canonicalJson());target=UUID.fromString(body.path("clusterBinding").path("targetId").asText());String ns=body.path("policy").path("namespace").asText();
   var store=mock(ConnectionStore.class);when(store.view(target)).thenReturn(new ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));when(store.kubernetesMaterial(target)).thenReturn(new ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token"));
   clients=new ExecutionClusterClients(json.writeValueAsString(List.of(Map.of("targetId",target.toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace",ns,"operations",List.of("RECOVER")))),json,store);
   binding=clients.binding(target,ns);((com.fasterxml.jackson.databind.node.ObjectNode)body).set("clusterBinding",json.valueToTree(binding));String text=json.writeValueAsString(body);plan=new K6PlanEnvelope.Prepared(text,K6PlanEnvelope.hash(text));server.configure(plan);
   scope=new ExecutionClusterClients.Scope(target,1,ns,body.path("workerName").asText(),null,ExecutionClusterClients.Access.RECOVER);
  }
  public void close(){server.close();}
  KubernetesWorkerRecovery.Found inspect(Resource resource,String uid) {
   try(var s=clients.openRecovery(scope,binding,plan,resource,null);var a=new KubernetesWorkerRecovery(s.client(),json)){return a.inspect(plan,resource,uid,false);}
  }
 }
 @Test void exactResourcesRequireStableReadbackThenUidAndVersionDeleteAndConfirmedAbsence() throws Exception {
  try(var f=new Fixture()){
   for(var resource:Resource.values()){
    var found=f.inspect(resource,null);assertThat(found.uid()).isNotBlank();assertThat(found.resourceVersion()).isNotBlank();
    var command=new KubernetesWorkerRecovery.Delete(resource,f.binding.namespace(),f.scope.workerName(),found.uid(),found.resourceVersion());
    try(var s=f.clients.openRecovery(f.scope,f.binding,f.plan,resource,command);var a=new KubernetesWorkerRecovery(s.client(),json)){assertThat(a.delete(command)).isEqualTo("ACKNOWLEDGED");}
    assertThat(f.inspect(resource,found.uid())).isNull();
   }
   assertThat(f.server.requests.stream().filter(r->r.startsWith("DELETE ")).count()).isEqualTo(3);
   assertThat(f.server.requests).allMatch(r->r.startsWith("GET ") || r.startsWith("DELETE "));
   for(int i=0;i<f.server.requests.size();i++)if(f.server.requests.get(i).startsWith("DELETE ")){var body=json.readTree(f.server.bodies.get(i));assertThat(body.path("preconditions").path("uid").asText()).isNotBlank();assertThat(body.path("preconditions").path("resourceVersion").asText()).isNotBlank();assertThat(body.has("gracePeriodSeconds")).isFalse();}
  }
 }
 @Test void sourceSecurityOwnershipAndIdentityDriftNeverAuthorizeDeletion() throws Exception {
  for(String drift:List.of("source","network","job-image","owner","uid"))try(var f=new Fixture()){
   Resource resource=Resource.FILES;
   switch(drift){case "source"->f.server.files.getData().put("scenario.js","changed");case "network"->{resource=Resource.NETWORK;f.server.network.getSpec().setEgress(List.of());}case "job-image"->{resource=Resource.JOB;f.server.job.getSpec().getTemplate().getSpec().getContainers().getFirst().image("foreign.invalid/image");}case "owner"->f.server.files.getMetadata().ownerReferences(List.of(new V1OwnerReference().apiVersion("v1").kind("Pod").name("foreign").uid("foreign").controller(true)));case "uid"->f.server.files.getMetadata().uid("replacement");}
   var selected=resource;assertThatThrownBy(()->f.inspect(selected,drift.equals("uid")?"files-uid":null)).isInstanceOf(KubernetesWorkerRecovery.Problem.class);assertThat(f.server.requests).noneMatch(r->r.startsWith("DELETE "));
  }
 }
 @Test void recoveryGrantCannotReadOtherResourcesWriteCreateOrChangeDeletionIdentity() throws Exception {
  try(var f=new Fixture()){
   assertThatThrownBy(()->f.clients.openBound(f.scope,f.binding)).isInstanceOf(ExecutionClusterClients.Problem.class);
   try(var s=f.clients.openRecovery(f.scope,f.binding,f.plan,Resource.FILES,null)){
    assertThatThrownBy(()->new CoreV1Api(s.client()).readNamespacedConfigMap("foreign",f.binding.namespace()).execute()).isInstanceOf(ApiException.class);
    assertThatThrownBy(()->new BatchV1Api(s.client()).readNamespacedJob(f.scope.workerName(),f.binding.namespace()).execute()).isInstanceOf(ApiException.class);
   }
   var command=new KubernetesWorkerRecovery.Delete(Resource.FILES,f.binding.namespace(),f.scope.workerName(),"files-uid","1");
   try(var s=f.clients.openRecovery(f.scope,f.binding,f.plan,Resource.FILES,command)){
    var forged=new V1DeleteOptions().preconditions(new V1Preconditions().uid("replacement").resourceVersion("1")).propagationPolicy("Foreground");
    assertThatThrownBy(()->new CoreV1Api(s.client()).deleteNamespacedConfigMap(f.scope.workerName(),f.binding.namespace()).body(forged).execute()).isInstanceOf(ApiException.class);
    assertThatThrownBy(()->new CoreV1Api(s.client()).readNamespacedConfigMap(f.scope.workerName(),f.binding.namespace()).execute()).isInstanceOf(ApiException.class);
   }
   assertThat(f.server.requests).isEmpty();
  }
 }
 record ServiceFixture(WorkerRecoveryService service,WorkerRecoveryStore store,RunIntents runs,ExecutionPlans plans,RunIntents.Run run){}
 ServiceFixture service(Fixture f,RunLifecycle.Status status,WorkerRecoveryStore.Row row,boolean before) throws Exception {
  var body=json.readTree(f.plan.canonicalJson());UUID id=UUID.fromString(body.path("runId").asText());
  var run=new RunIntents.Run(id,UUID.fromString(body.path("snapshotId").asText()),f.target,UUID.randomUUID(),status,2,OffsetDateTime.now().plusMinutes(5),UUID.fromString(body.path("attemptId").asText()),UUID.fromString(body.path("shardId").asText()),f.scope.workerName(),25,List.of(),"UNEVALUATED",before?null:"job-uid",null);
  var runs=mock(RunIntents.class);when(runs.get(id)).thenReturn(run);var plans=mock(ExecutionPlans.class);when(plans.get(id)).thenReturn(new ExecutionPlans.Saved(UUID.randomUUID(),id,run.attemptId(),UUID.fromString(body.path("submitOperationId").asText()),2,f.plan.sha256(),f.plan.canonicalJson(),"admin",OffsetDateTime.now()));when(plans.clusterBinding(id)).thenReturn(f.binding);
  var store=mock(WorkerRecoveryStore.class);when(store.rows(id)).thenReturn(List.of(row));when(store.beforeJob(id)).thenReturn(before);
  return new ServiceFixture(new WorkerRecoveryService(runs,plans,f.clients,store,mock(RunObservations.class),json),store,runs,plans,run);
 }
 @Test void unknownMissingCreateRetainsRecoveryProtectionAndNeverRetriesCreationOrDeletes() throws Exception {
  try(var f=new Fixture()){
   f.server.removed.add("files");var row=new WorkerRecoveryStore.Row(UUID.randomUUID(),Resource.FILES,null,null);var s=service(f,RunLifecycle.Status.STOPPING,row,true);
   assertThat(s.service().cleanup(s.run().id(),false)).isFalse();verify(s.store(),never()).absent(any(),any(),any());verify(s.store(),never()).completeBeforeJob(any());assertThat(f.server.requests).hasSize(2).allMatch(r->r.startsWith("GET "));
  }
 }
 @Test void partialStartCanBeCleanedOnlyAfterAnIdentifiedResourceAndDurableDeleteClaim() throws Exception {
  try(var f=new Fixture()){
   var id=UUID.randomUUID();var row=new WorkerRecoveryStore.Row(id,Resource.FILES,null,null);var s=service(f,RunLifecycle.Status.STOPPING,row,true);
   var operation=UUID.randomUUID();
   when(s.store().claimDelete(eq(s.run().id()),eq(id),any())).thenAnswer(c->{assertThat(f.server.requests).noneMatch(r->r.startsWith("DELETE "));KubernetesWorkerRecovery.Found found=c.getArgument(2);return new WorkerRecoveryStore.DeleteClaim(operation,found.uid(),found.resourceVersion());});
   doAnswer(c->{when(s.store().rows(s.run().id())).thenReturn(List.of(new WorkerRecoveryStore.Row(id,Resource.FILES,"files-uid",OffsetDateTime.now())));return null;}).when(s.store()).absent(eq(s.run().id()),eq(id),eq("files-uid"));
   assertThat(s.service().cleanup(s.run().id(),false)).isTrue();verify(s.store()).completeBeforeJob(s.run().id());verify(s.store()).completeWorkflow(s.run().id());assertThat(f.server.requests.stream().filter(r->r.startsWith("DELETE ")).count()).isEqualTo(1);
   verify(s.store()).finishDelete(operation,"ACKNOWLEDGED");
  }
 }
 @Test void uncollectedSummaryBlocksJobDeletionAndAnUncertainDeleteIsNeverResent() throws Exception {
  try(var f=new Fixture()){
   var row=new WorkerRecoveryStore.Row(UUID.randomUUID(),Resource.JOB,"job-uid",null);var s=service(f,RunLifecycle.Status.COMPLETED,row,false);
   assertThat(s.service().cleanup(s.run().id(),false)).isFalse();verify(s.store(),never()).claimDelete(any(),any(),any());
   when(s.store().claimDelete(any(),any(),any())).thenReturn(null);
   assertThat(s.service().cleanup(s.run().id(),true)).isFalse();assertThat(f.server.requests).noneMatch(r->r.startsWith("DELETE "));
  }
 }
}
