package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.*;
import dev.loadpilot.integrations.k6.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.username=admin","platform.password=integration-test-password-123","platform.execution.monitor-enabled=true",
 "platform.execution.runtime-policy-json={\"namespace\":\"loadpilot-runners\",\"imageDigest\":\"example.invalid/k6@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"serviceAccount\":\"loadpilot-runner\",\"runtimeClass\":\"sandboxed\",\"temporaryStorageMiB\":32}",
 "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}","spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}","spring.datasource.password=${TEST_DATABASE_PASSWORD}"})
@AutoConfigureMockMvc @WithMockUser(username="admin",roles="OPERATOR")
class StartFlowIntegrationTest {
 @TestConfiguration(proxyBeanMethods=false) static class Destinations {
  @Bean @Primary ExecutionDestinationPolicy fixtureDestinations(){return new ExecutionDestinationPolicy(Map.of("https://fixture.invalid",List.of("192.0.2.10")),h->List.of(java.net.InetAddress.getByName("192.0.2.10")),Duration.ofSeconds(2));}
 }
 @Autowired ApplicationContext context;@Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 @Autowired RunIntents runs;@Autowired RunStartProcessor processor;@Autowired StartWorkflows queue;@Autowired RunMonitor monitor;@Autowired RunMonitorStore monitorQueue;@Autowired ExperimentSnapshots.Store snapshots;
 @Autowired RunPartialResults partialResults;@Autowired WorkerHttpMetrics workerMetrics;
 @MockitoBean RuntimeAdmission admission;@MockitoBean ExecutionClusterClients clients;@MockitoBean SystemInspector inspector;
 // Explicit deterministic scheduler control; real schedule behavior has its own tests, no cross-test background I/O.
 @MockitoBean RunStartSchedule startSchedule;@MockitoBean RunMonitorSchedule monitorSchedule;@MockitoBean RunPartialResultSchedule partialSchedule;
 ExperimentSnapshotIntegrationTest helper;KubernetesWorkerActivatorTest.Fixture server;
 final Set<UUID> owned=new HashSet<>();
 @BeforeEach void fixtures() throws Exception {
  helper=new ExperimentSnapshotIntegrationTest();context.getAutowireCapableBeanFactory().autowireBean(helper);
  server=new KubernetesWorkerActivatorTest().new Fixture();server.fresh=true;
  when(admission.available()).thenReturn(true);
  when(admission.verify(any())).thenAnswer(c->{K6PlanEnvelope.Prepared p=c.getArgument(0);server.configure(p);return new RuntimeAdmission.Grant(UUID.randomUUID(),p.sha256(),Instant.now().plusSeconds(600),"a".repeat(64));});
 }
 @AfterEach void cleanup(){
  try{for(UUID id:owned){helper.closeObservationFixture(id);db.update("UPDATE experiment_start_workflow SET state='HALTED',reason='STOP_REQUESTED',lease_token=NULL,lease_until=NULL WHERE run_id=? AND state IN ('QUEUED','DISPATCHING')",id);}}finally{if(server!=null)server.close();}
 }
 RunIntents.Start input() throws Exception {
  var request=helper.runRequest("export default function(){}",60);UUID target=snapshots.get(request.snapshotId()).inputs().target().id();
  var store=mock(ConnectionStore.class);when(store.view(target)).thenReturn(new ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));when(store.kubernetesMaterial(target)).thenReturn(new ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token"));
  String config=json.writeValueAsString(List.of(Map.of("targetId",target.toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace","loadpilot-runners","operations",List.of("READ","STOP","PREPARE","ACTIVATE","METRICS","RECOVER"))));
  var real=new ExecutionClusterClients(config,json,store);
  when(clients.binding(any(),anyString())).thenAnswer(c->real.binding(c.getArgument(0),c.getArgument(1)));
  when(clients.openPreparation(any(),any(),any(),any())).thenAnswer(c->real.openPreparation(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3)));
  when(clients.openActivationRead(any(),any())).thenAnswer(c->real.openActivationRead(c.getArgument(0),c.getArgument(1)));
  when(clients.openActivationPatch(any(),any(),any())).thenAnswer(c->real.openActivationPatch(c.getArgument(0),c.getArgument(1),c.getArgument(2)));
  when(clients.openBound(any(),any())).thenAnswer(c->real.openBound(c.getArgument(0),c.getArgument(1)));
  when(clients.openWorkerLogs(any(),any(),any())).thenAnswer(c->real.openWorkerLogs(c.getArgument(0),c.getArgument(1),c.getArgument(2)));
  when(clients.openRecovery(any(),any(),any(),any(),any())).thenAnswer(c->real.openRecovery(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3),c.getArgument(4)));
  when(inspector.resources(target)).thenAnswer(c->{var now=Instant.now();var ready=new Inspection.Check("READY","fixture",now,"Kubernetes PodMetrics",200);return new Inspection(target,1,1,now,null,ready,ready,null,null,List.of(new Inspection.Pod("target-api","Running",true,0,null,null,null,null,new Inspection.Usage("READY",now,"30s",java.math.BigDecimal.valueOf(50),java.math.BigDecimal.valueOf(67108864)))));});
  return request;
 }
 UUID start(RunIntents.Start request) throws Exception {
  String body=mvc.perform(post("/api/experiments/runs").with(csrf()).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED")).andReturn().getResponse().getContentAsString();UUID id=UUID.fromString(json.readTree(body).path("id").asText());owned.add(id);return id;
 }
 @Test void publicStartDrivesScopedPreparationActivationLiveMetricsAndStopWithoutDuplicateEffects() throws Exception {
  var request=input();UUID id=start(request);
  try {
   assertThat(queue.read(id).state()).isEqualTo("QUEUED");assertThat(server.requests).isEmpty();
   mvc.perform(post("/api/experiments/runs").with(csrf()).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id.toString()));
   assertThat(db.queryForObject("SELECT count(*) FROM experiment_start_workflow WHERE run_id=?",Integer.class,id)).isEqualTo(1);
   assertThat(processor.process(id)).isTrue();assertThat(queue.read(id).state()).isEqualTo("OBSERVING");assertThat(server.requests.stream().filter(s->s.startsWith("POST ")).count()).isEqualTo(3);assertThat(server.requests.stream().filter(s->s.startsWith("PATCH ")).count()).isEqualTo(1);
   assertThat(processor.process(id)).isFalse();assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.SUBMITTING);
   monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.RUNNING);
   var sampler=new RunPartialResultSchedule(partialResults,runs,workerMetrics);
   sampler.poll();
   var retained=partialResults.latest(runs.get(id));
   assertThat(retained.requestCount()).isEqualTo(2);
   int requests=server.requests.size();sampler.poll();assertThat(server.requests).hasSize(requests);
   mvc.perform(get("/api/experiments/runs/"+id+"/metrics")).andExpect(status().isOk()).andExpect(jsonPath("$.http.state").value("READY")).andExpect(jsonPath("$.http.requestCount").value(2)).andExpect(jsonPath("$.http.p95LatencyMs").value(290.0)).andExpect(jsonPath("$.resources.cpuMillicores").value(50)).andExpect(jsonPath("$.verdict").value("UNEVALUATED"));
   mvc.perform(post("/api/experiments/runs/"+id+"/stop").with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPING"));
   monitorQueue.wake(id);monitor.process(id);assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.STOPPING);
   monitorQueue.wake(id);monitor.process(id);assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.CANCELLED);
   assertThat(server.requests.stream().filter(s->s.startsWith("DELETE ") && s.contains("/jobs/")).count()).isEqualTo(1);
   assertCleanup(id);
   assertThat(monitorQueue.read(id).outcome()).isEqualTo("CLEANED_NO_RESULT");
   when(admission.available()).thenReturn(false);
   mvc.perform(post("/api/experiments/runs").with(csrf()).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCELLED"));
   server.close();clearInvocations(clients);
   mvc.perform(get("/api/experiments/runs/"+id+"/result"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.resultState").value("NOT_COLLECTED"))
      .andExpect(jsonPath("$.verdict").value("UNEVALUATED"))
      .andExpect(jsonPath("$.partialHttp.identity.runId").value(id.toString()))
      .andExpect(jsonPath("$.partialHttp.identity.jobUid").value(retained.identity().jobUid()))
      .andExpect(jsonPath("$.partialHttp.planSha256").value(retained.planSha256()))
      .andExpect(jsonPath("$.partialHttp.requestCount").value(2))
      .andExpect(jsonPath("$.partialHttp.errorCount").value(1))
      .andExpect(jsonPath("$.partialHttp.coverage").value("PARTIAL_HTTP_WINDOW_NOT_SLO"));
   verifyNoInteractions(clients);
  }finally{helper.closeObservationFixture(id);}
 }
 @Test void unavailableSampleAndStopRaceNeverPersistUnverifiedPartialEvidence() throws Exception {
  UUID id=start(input());processor.process(id);
  monitorQueue.wake(id);monitor.process(id);
  var run=runs.get(id);
  assertThat(run.status()).isEqualTo(RunLifecycle.Status.RUNNING);
  server.metricLogOverride="partial line";
  new RunPartialResultSchedule(partialResults,runs,workerMetrics).poll();
  assertThat(partialResults.latest(run)).isNull();
  server.metricLogOverride=null;
  var inFlight=workerMetrics.readForRetention(run);
  assertThat(inFlight.state()).isEqualTo("READY");
  runs.stop(id,"admin");
  assertThat(partialResults.retain(id,inFlight)).isFalse();
  monitorQueue.wake(id);monitor.process(id);
  monitorQueue.wake(id);monitor.process(id);
  assertCleanup(id);
  mvc.perform(get("/api/experiments/runs/"+id+"/result"))
     .andExpect(jsonPath("$.status").value("CANCELLED"))
     .andExpect(jsonPath("$.partialHttp").doesNotExist());
 }
 @Test void queuedStopAndUnavailableAdmissionCannotCreateWorkers() throws Exception {
  var request=input();when(admission.available()).thenReturn(false);
  mvc.perform(post("/api/experiments/runs").with(csrf()).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EXECUTION_UNAVAILABLE"));
  assertThat(runs.findRequest(request.requestKey())).isNull();when(admission.available()).thenReturn(true);UUID id=start(request);
  runs.stop(id,"admin");processor.process(id);assertThat(queue.read(id).state()).isEqualTo("HALTED");assertThat(server.requests).isEmpty();verify(admission,never()).verify(any());
 }
 @Test void publicStartCompletesIntoDurableResultsReadableAfterWorkerApiCloses() throws Exception {
  UUID id=start(input());server.legacyTemplateTimestamp=true;processor.process(id);
  finishWorker(0,helper.finalMessage(runs.get(id),server.job).put("durationMs",1000).toString());
  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.COMPLETED);
  assertCleanup(id);
  assertThat(monitorQueue.read(id).outcome()).isEqualTo("RESULT_SAVED");server.close();clearInvocations(clients);
  mvc.perform(get("/api/experiments/runs/"+id+"/result")).andExpect(status().isOk()).andExpect(jsonPath("$.resultState").value("AVAILABLE")).andExpect(jsonPath("$.verdict").value("UNEVALUATED")).andExpect(jsonPath("$.result.evidence.summary.httpRequests").value(100));verifyNoInteractions(clients);
 }
 @Test void workerFailureWithoutSummaryKeepsTerminationEvidenceAndFinishesCleanup() throws Exception {
  UUID id=start(input());processor.process(id);
  finishWorker(137,null);
  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.FAILED);
  assertCleanup(id);
  server.close();clearInvocations(clients);
  mvc.perform(get("/api/experiments/runs/"+id+"/result"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.resultState").value("SUMMARY_UNAVAILABLE"))
    .andExpect(jsonPath("$.verdict").value("UNEVALUATED"))
    .andExpect(jsonPath("$.result.evidence.exitCode").value(137))
    .andExpect(jsonPath("$.result.evidence.summary").doesNotExist());
  verifyNoInteractions(clients);
 }
 @Test void cleanupRetriesADefiniteVersionConflictWithFreshPreconditionsAndRetainsBothReceipts() throws Exception {
  UUID id=start(input());processor.process(id);
  finishWorker(0,helper.finalMessage(runs.get(id),server.job).put("durationMs",1000).toString());
  server.cleanupVersionConflicts=1;
  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.COMPLETED);
  assertThat(server.removed).isEmpty();
  assertThat(monitorQueue.read(id).finished()).isFalse();
  assertThat(jobDeleteReceipts(id)).containsExactly("11:CONFLICT");
  mvc.perform(get("/api/experiments/runs/"+id+"/result")).andExpect(jsonPath("$.resultState").value("AVAILABLE"));

  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  assertCleanup(id);
  assertThat(jobDeleteReceipts(id)).containsExactly("11:CONFLICT","12:ACKNOWLEDGED");
  assertThatThrownBy(()->db.update("""
      UPDATE experiment_resource_recovery SET delete_outcome='DENIED' WHERE run_id=? AND delete_outcome='CONFLICT'
      """,id)).hasMessageContaining("delete intent and final outcome are immutable");
  assertThatThrownBy(()->db.update("""
      UPDATE experiment_resource_delete_retry a SET outcome='DENIED'
      FROM experiment_resource_recovery r WHERE a.preparation_id=r.preparation_id AND r.run_id=?
      """,id)).hasMessageContaining("Cleanup delete intent and final outcome are immutable");
 }
 @Test void cleanupDoesNotResendAnUncertainDeleteAfterResourceVersionChanges() throws Exception {
  UUID id=start(input());processor.process(id);
  finishWorker(0,helper.finalMessage(runs.get(id),server.job).put("durationMs",1000).toString());
  server.cleanupDeleteError=500;
  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  server.cleanupDeleteError=0;
  server.job.getMetadata().resourceVersion("12");
  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  assertThat(jobDeleteReceipts(id)).containsExactly("11:UNCERTAIN");
  assertThat(server.removed).isEmpty();
  assertThat(monitorQueue.read(id).finished()).isFalse();
  assertThat(server.requests.stream().filter(s->s.startsWith("DELETE ") && s.contains("/jobs/")).count()).isEqualTo(1);
 }
 @Test void cleanupLimitsRepeatedVersionConflictsToThreeAttempts() throws Exception {
  UUID id=start(input());processor.process(id);
  finishWorker(0,helper.finalMessage(runs.get(id),server.job).put("durationMs",1000).toString());
  server.cleanupVersionConflicts=4;
  for(int poll=0;poll<4;poll++) {
   monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  }
  assertThat(jobDeleteReceipts(id)).containsExactly("11:CONFLICT","12:CONFLICT","13:CONFLICT");
  assertThat(server.removed).isEmpty();
  assertThat(monitorQueue.read(id).finished()).isFalse();
  assertThat(server.requests.stream().filter(s->s.startsWith("DELETE ") && s.contains("/jobs/")).count()).isEqualTo(3);
 }
 private List<String> jobDeleteReceipts(UUID id) {
  return db.queryForList("""
      SELECT a.resource_version || ':' || a.outcome FROM experiment_resource_delete_attempt a
      JOIN experiment_resource_recovery r ON r.preparation_id=a.preparation_id
      JOIN experiment_worker_preparation p ON p.id=r.preparation_id
      WHERE r.run_id=? AND p.resource='JOB' ORDER BY a.attempt_number
      """,String.class,id);
 }
 @Test void terminalJobWithoutAnyWorkerPodCanBeCleanedUpWithoutInventingResults() throws Exception {
  UUID id=start(input());processor.process(id);
  server.job.status(new io.kubernetes.client.openapi.models.V1JobStatus().conditions(List.of(new io.kubernetes.client.openapi.models.V1JobCondition().type("Failed").status("True"))));
  server.podMissing=true;
  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.FAILED);
  assertCleanup(id);
  assertThat(monitorQueue.read(id).outcome()).isEqualTo("CLEANED_NO_RESULT");
  mvc.perform(get("/api/experiments/runs/"+id+"/result"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.resultState").value("NOT_COLLECTED"))
    .andExpect(jsonPath("$.verdict").value("UNEVALUATED"));
 }
 @Test void completedRunRetainsTargetProtectionAndDependentResourcesUntilThePodDisappears() throws Exception {
  var request=input();UUID id=start(request);processor.process(id);
  finishWorker(0,helper.finalMessage(runs.get(id),server.job).put("durationMs",1000).toString());
  server.podLingersAfterDelete=true;
  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.COMPLETED);
  assertThat(server.removed).containsExactly("job");
  assertThat(monitorQueue.read(id).finished()).isFalse();
  assertThat(db.queryForObject("SELECT count(*) FROM experiment_resource_recovery WHERE run_id=? AND absence_verified_at IS NOT NULL",Integer.class,id)).isZero();
  mvc.perform(get("/api/experiments/runs/"+id+"/result")).andExpect(jsonPath("$.resultState").value("AVAILABLE"));

  UUID draft=snapshots.get(request.snapshotId()).inputs().draftId();
  db.update("UPDATE experiment_draft SET revision=revision+1 WHERE id=?",draft);
  var preview=snapshots.preview(draft);
  var reviewed=snapshots.finalizeInputs(draft,new ExperimentSnapshots.Finalize(preview.inputs().draftRevision(),preview.sha256(),true),"admin");
  var next=new RunIntents.Start(reviewed.id(),UUID.randomUUID(),reviewed.sha256(),true);
  mvc.perform(post("/api/experiments/runs").with(csrf()).contentType("application/json").content(json.writeValueAsString(next)))
    .andExpect(status().isConflict());
  assertThat(runs.findRequest(next.requestKey())).isNull();

  server.podLingersAfterDelete=false;
  monitorQueue.wake(id);assertThat(monitor.process(id)).isTrue();
  assertCleanup(id);
  assertThat(server.requests.stream().filter(s->s.startsWith("DELETE ") && s.contains("/jobs/")).count()).isEqualTo(1);
  start(next);
 }
 private void finishWorker(int exitCode,String message) {
  var job=server.job;
  job.status(new io.kubernetes.client.openapi.models.V1JobStatus().conditions(List.of(new io.kubernetes.client.openapi.models.V1JobCondition().type(exitCode==0?"Complete":"Failed").status("True"))));
  var pod=server.selectedPod();pod.spec(job.getSpec().getTemplate().getSpec());
  var now=OffsetDateTime.now(ZoneOffset.UTC);
  var ended=new io.kubernetes.client.openapi.models.V1ContainerStateTerminated().exitCode(exitCode).startedAt(now.minusSeconds(2)).finishedAt(now).message(message);
  pod.status(new io.kubernetes.client.openapi.models.V1PodStatus().phase(exitCode==0?"Succeeded":"Failed").containerStatuses(List.of(new io.kubernetes.client.openapi.models.V1ContainerStatus().name("k6").image(job.getSpec().getTemplate().getSpec().getContainers().getFirst().getImage()).imageID("sha256:"+"a".repeat(64)).ready(false).restartCount(0).state(new io.kubernetes.client.openapi.models.V1ContainerState().terminated(ended)))));
  server.overridePod=pod;
 }
 private void assertCleanup(UUID id) {
  assertThat(server.removed).containsExactlyInAnyOrder("job","network","files");
  assertThat(db.queryForObject("SELECT count(*) FROM experiment_resource_recovery WHERE run_id=? AND absence_verified_at IS NOT NULL",Integer.class,id)).isEqualTo(3);
  assertThat(monitorQueue.read(id).finished()).isTrue();
 }
 @Test void rejectedAdmissionReleasesOnlyAnEffectFreeRunAndDoesNotExposePrivateErrors() throws Exception {
  UUID id=start(input());doThrow(new IllegalStateException("private upstream URL token")).when(admission).verify(any());processor.process(id);
  assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.FAILED);assertThat(queue.read(id).state()).isEqualTo("FAILED");assertThat(server.requests).isEmpty();
  mvc.perform(get("/api/experiments/runs/"+id+"/start-workflow")).andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("START_REJECTED")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private upstream"))));
 }
 @Test void uncertainCreateRemainsProtectedAndIsNotRetriedAfterRestart() throws Exception {
  UUID id=start(input());server.createStatus=500;
  try {processor.process(id);assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.STOPPING);assertThat(queue.read(id).state()).isEqualTo("RECOVERY_REQUIRED");assertThat(processor.process(id)).isFalse();assertThat(server.requests.stream().filter(s->s.startsWith("POST ")).count()).isEqualTo(1);assertThat(server.requests).noneMatch(s->s.startsWith("PATCH "));}
  finally{helper.closeObservationFixture(id);}
 }
 @Test void concurrentStartCommitsOneRunAndQueueAndRequiresOperatorAndCsrf() throws Exception {
  var request=input();String body=json.writeValueAsString(request);
  mvc.perform(post("/api/experiments/runs").with(csrf()).with(anonymous()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/experiments/runs").with(csrf()).with(user("viewer").roles("VIEWER")).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(post("/api/experiments/runs").contentType("application/json").content(body)).andExpect(status().isForbidden());
  var starts=context.getBean(RunStarts.class);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   Callable<RunIntents.Run> task=()->{ready.countDown();go.await();return starts.request(request,"admin");};var a=pool.submit(task);var b=pool.submit(task);assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();go.countDown();
   UUID id=a.get(5,TimeUnit.SECONDS).id();owned.add(id);assertThat(b.get(5,TimeUnit.SECONDS).id()).isEqualTo(id);
   assertThat(db.queryForObject("SELECT count(*) FROM experiment_start_workflow WHERE run_id=?",Integer.class,id)).isEqualTo(1);assertThat(server.requests).isEmpty();
   mvc.perform(get("/api/experiments/runs/"+id+"/start-workflow").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
  }
 }
 @Test void expiredCoordinatorLeaseResumesAcknowledgedResourcesWithoutRepeatingCreation() throws Exception {
  UUID id=start(input());var abandoned=queue.claim(id);helper.dispatch.prepare(id);var candidate=helper.preflight.prepare(id);var grant=admission.verify(candidate.plan());
  var saved=helper.preflight.persist(id,candidate.plan().sha256(),candidate.destination(),"admin");queue.authorize(abandoned,saved,grant);
  context.getBean(WorkerPreparationService.class).prepare(id,saved.sha256(),KubernetesWorkerPreparer.Resource.FILES);
  db.update("UPDATE experiment_start_workflow SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?",id);
  processor.process(id);assertThat(queue.read(id).state()).isEqualTo("OBSERVING");assertThat(server.requests.stream().filter(s->s.startsWith("POST ")).count()).isEqualTo(3);
  queue.finish(abandoned,"FAILED","START_REJECTED");assertThat(queue.read(id).state()).isEqualTo("OBSERVING");
  assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE action='RUN_ADMISSION_RECORDED' AND object_id=?",Integer.class,id)).isEqualTo(1);
  assertThatThrownBy(()->db.update("UPDATE experiment_start_workflow SET admission_json=NULL,admission_sha256=NULL,plan_id=NULL WHERE run_id=?",id)).hasMessageContaining("Start admission is immutable");
 }
 @Test void revokedOperatorCannotDispatchPreviouslyQueuedWork() throws Exception {
  UUID id=start(input());Boolean enabled=db.queryForObject("SELECT enabled FROM users WHERE username='admin'",Boolean.class);
  try{db.update("UPDATE users SET enabled=false WHERE username='admin'");processor.process(id);assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.FAILED);assertThat(server.requests).isEmpty();verify(admission,never()).verify(any());}
  finally{db.update("UPDATE users SET enabled=? WHERE username='admin'",enabled);}
 }
 @Test void admissionMustCoverTheWholeRunDeadlineBeforeAnyResourceCreation() throws Exception {
  UUID id=start(input());doAnswer(c->{K6PlanEnvelope.Prepared p=c.getArgument(0);server.configure(p);return new RuntimeAdmission.Grant(UUID.randomUUID(),p.sha256(),Instant.now().plusSeconds(125),"a".repeat(64));}).when(admission).verify(any());
  processor.process(id);assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.FAILED);assertThat(queue.read(id).admission()).isNull();assertThat(server.requests).isEmpty();
 }
}
