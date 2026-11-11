package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.experiments.ExperimentSnapshots;
import dev.loadpilot.experiments.RunIntents;
import dev.loadpilot.experiments.RunLifecycle;
import dev.loadpilot.experiments.RunObservations;
import dev.loadpilot.experiments.RunDispatch;
import dev.loadpilot.experiments.ExecutionPlans;
import dev.loadpilot.experiments.ExecutionPreflight;
import dev.loadpilot.experiments.WorkerObservationService;
import dev.loadpilot.experiments.WorkerStopDispatch;
import dev.loadpilot.experiments.WorkerControlService;
import dev.loadpilot.integrations.k6.KubernetesWorkerStopper;
import dev.loadpilot.integrations.k6.KubernetesWorkerCollector;
import dev.loadpilot.integrations.k6.ExecutionDestinationPolicy;
import dev.loadpilot.integrations.k6.ExecutionRuntimePolicy;
import dev.loadpilot.integrations.k6.ExecutionClusterClients;
import dev.loadpilot.integrations.k6.K6JobPlan;
import dev.loadpilot.scenarios.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.username=admin", "platform.password=integration-test-password-123",
        "platform.execution.destinations-json=",
        "platform.execution.runtime-policy-json=",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}", "spring.datasource.password=${TEST_DATABASE_PASSWORD}"})
@AutoConfigureMockMvc @WithMockUser(username="admin", roles="OPERATOR") @Transactional
class ExperimentSnapshotIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired ScenarioStore scenarios; @Autowired ScenarioValidationApi.Store validations;
    @Autowired ScenarioApproval.Store approvals; @Autowired ExperimentSnapshots.Store snapshots;
    @Autowired RunIntents runs;
    @Autowired RunObservations observations;
    @Autowired RunDispatch dispatch;
    @Autowired ExecutionPlans plans;
    @Autowired ExecutionPreflight preflight;
    @Autowired dev.loadpilot.integrations.k6.ExecutionSourcePolicy sourcePolicy;
    @Autowired ScriptParser scriptParser;
    @Autowired dev.loadpilot.experiments.RunStarts starts;
    @Autowired dev.loadpilot.experiments.StartWorkflows startWorkflows;
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void startupTimeoutCommitsStopOnceAndKeepsProtectionUntilTermination() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest("export default function(){}",1),"admin").id());
        try {
            var started=runs.submissionStartedAt(run.id());assertThat(started).isNotNull();
            assertThat(runs.enforceStartup(run.id()).status()).isEqualTo(RunLifecycle.Status.SUBMITTING);
            Thread.sleep(1100);
            var stopping=runs.enforceStartup(run.id());assertThat(stopping.status()).isEqualTo(RunLifecycle.Status.STOPPING);
            assertThat(monitorQueue.read(run.id()).stopReason()).isEqualTo("STARTUP");
            assertThat(runs.enforceStartup(run.id())).isEqualTo(stopping);assertThat(runs.submissionStartedAt(run.id())).isEqualTo(started);
            assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE action='RUN_STARTUP_UNCONFIRMED' AND object_id=?",Integer.class,run.id())).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM experiment_run WHERE target_id=? AND status IN ('SUBMITTING','RUNNING','STOPPING','RECOVERY_REQUIRED')",Integer.class,run.targetId())).isEqualTo(1);
            var confirmed=observations.record(run.id(),observation(stopping,RunLifecycle.WorkerState.TERMINATED,true));
            assertThat(confirmed.status()).isEqualTo(RunLifecycle.Status.FAILED);assertThat(confirmed.verdict()).isEqualTo("UNEVALUATED");
            assertThat(confirmed.operations().stream().filter(op->op.kind().equals("STOP")).findFirst().orElseThrow().status()).isEqualTo("CONFIRMED");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->db.update("UPDATE experiment_attempt SET submission_started_at=clock_timestamp() WHERE id=?",run.attemptId())).hasMessageContaining("Attempt clocks are immutable");
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void confirmedStartupClockSurvivesLaterUnreachableEvidenceWithoutNewStartupBudget() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest("export default function(){}",1),"admin").id());
        try {
            var running=observations.record(run.id(),observation(run,RunLifecycle.WorkerState.RUNNING,false));
            var startup=db.queryForObject("SELECT startup_observed_at FROM experiment_attempt WHERE id=?",java.time.OffsetDateTime.class,run.attemptId());assertThat(startup).isNotNull();
            Thread.sleep(1100);
            var offline=observations.record(run.id(),observation(running,RunLifecycle.WorkerState.UNREACHABLE,false));
            assertThat(runs.enforceStartup(run.id())).isEqualTo(offline);assertThat(offline.status()).isEqualTo(RunLifecycle.Status.RECOVERY_REQUIRED);
            assertThat(monitorQueue.read(run.id()).stopReason()).isNull();
            assertThat(db.queryForObject("SELECT startup_observed_at FROM experiment_attempt WHERE id=?",java.time.OffsetDateTime.class,run.attemptId())).isEqualTo(startup);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->db.update("UPDATE experiment_attempt SET startup_observed_at=NULL WHERE id=?",run.attemptId())).hasMessageContaining("Attempt clocks are immutable");
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void monitorPersistsStartupStopWhenObservationCannotOpenItsClient() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest("export default function(){}",1),"admin").id());
        try {
            var control=org.mockito.Mockito.mock(WorkerControlService.class);var results=org.mockito.Mockito.mock(dev.loadpilot.experiments.WorkerResultService.class);
            org.mockito.Mockito.when(control.observe(run.id())).thenAnswer(call->{Thread.sleep(1100);throw new IllegalStateException("private upstream details");});
            assertThat(new dev.loadpilot.experiments.RunMonitor(monitorQueue,runs,control,results).process(run.id())).isTrue();
            assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.STOPPING);assertThat(monitorQueue.read(run.id()).stopReason()).isEqualTo("STARTUP");
            assertThat(monitorQueue.read(run.id()).outcome()).isEqualTo("UNAVAILABLE");
            org.mockito.Mockito.verifyNoInteractions(results);org.mockito.Mockito.verify(control,org.mockito.Mockito.never()).dispatchPendingStop(run.id());
            mvc.perform(get("/api/experiments/runs/"+run.id()+"/monitor")).andExpect(status().isOk()).andExpect(jsonPath("$.monitor.stopReason").value("STARTUP"));
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void executionSourceRechecksExactApprovedBytesBeforePlanCreationPreparationAndActivation() throws Exception {
        var allowed="import http from 'k6/http';export default function(){http.get('https://fixture.invalid/api',{timeout:500,redirects:0});}";
        for(String source:List.of(allowed,
                "import http from 'k6/http';export default function(){http.get('https://fixture.invalid/');}",
                "import http from 'k6/http';export default function(){http.get('https://fixture.invalid/',{timeout:'60s',redirects:0});}",
                "import http from 'k6/http';export default function(){http.get('https://fixture.invalid/',{timeout:1000,redirects:3});}",
                "import http from 'k6/http';export default function(){http.get('https://other.invalid/',{timeout:1000,redirects:0});}")) {
            assertThat(scriptParser.parse(source).policyAccepted()).isTrue(); // Static approval alone does not authorize execution.
            var run=runs.claimSubmission(runs.reserve(runRequest(source),"admin").id());
            try(var destinations=new ExecutionDestinationPolicy(Map.of("https://fixture.invalid",List.of("192.0.2.10")),host->List.of(java.net.InetAddress.getByName("192.0.2.10")),java.time.Duration.ofSeconds(2))) {
                var clusters=clusterFixture(run);var gate=new ExecutionPreflight(runs,snapshots,plans,destinations,new ExecutionRuntimePolicy(executionPolicy()),clusters,sourcePolicy);
                if(source.equals(allowed)) {
                    var candidate=gate.prepare(run.id());var saved=gate.persist(run.id(),candidate.plan().sha256(),candidate.destination(),"admin");
                    assertThat(gate.verifySaved(run.id(),saved.sha256(),candidate.destination())).isEqualTo(saved);
                    assertThat(gate.verifyPrepared(run.id(),saved.sha256(),candidate.destination())).isEqualTo(saved);
                    var body=json.readTree(saved.canonicalJson());assertThat(body.path("files").path("data").path("scenario.js").asText()).isEqualTo(source);
                    assertThat(body.path("scriptSha256").asText()).isEqualTo(dev.loadpilot.integrations.k6.K6PlanEnvelope.hash(source));
                    assertThat(body.path("requiredSourcePolicy").path("version").asText()).isEqualTo("literal-http-v1");
                    assertThat(body.path("requiredSourcePolicy").path("requestTimeoutMs").asInt()).isEqualTo(1000);
                } else {
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->gate.prepare(run.id())).isInstanceOf(dev.loadpilot.experiments.ExperimentDrafts.Problem.class).hasMessageStartingWith("Script does not meet execution requirements");
                    org.mockito.Mockito.verifyNoInteractions(clusters);
                    assertThat(db.queryForObject("SELECT count(*) FROM experiment_execution_plan WHERE run_id=?",Integer.class,run.id())).isZero();
                    var binding=clusters.binding(run.targetId(),executionPolicy().namespace());
                    // Building/storing a plan through an internal pure primitive is deliberately not execution authority.
                    var preview=plans.preview(run.id(),executionPolicy(),executionTarget(),binding);var saved=plans.persist(run.id(),preview.sha256(),executionPolicy(),executionTarget(),binding,"admin");
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->gate.persist(run.id(),saved.sha256(),executionTarget(),"admin")).isInstanceOf(dev.loadpilot.experiments.ExperimentDrafts.Problem.class);
                    var preparation=new dev.loadpilot.experiments.WorkerPreparationDispatch(preparationReceipts,runs,plans,gate,json);
                    var preparer=org.mockito.Mockito.mock(dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->preparation.request(run.id(),saved.sha256(),dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.FILES,preparer)).isInstanceOf(dev.loadpilot.experiments.ExperimentDrafts.Problem.class);
                    org.mockito.Mockito.verifyNoInteractions(preparer);
                    assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_preparation WHERE plan_id=?",Integer.class,saved.id())).isZero();
                    var activation=new dev.loadpilot.experiments.WorkerActivationDispatch(runs,plans,gate,activationReceipts,json);
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->activation.activate(run.id(),saved.sha256(),(p,r)->{throw new AssertionError("No inspection expected");},(c,op)->{throw new AssertionError("No activation expected");})).isInstanceOf(dev.loadpilot.experiments.ExperimentDrafts.Problem.class);
                    assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_activation WHERE plan_id=?",Integer.class,saved.id())).isZero();
                    assertThat(plans.get(run.id())).isEqualTo(saved);
                }
            } finally {closeObservationFixture(run.id());}
        }
    }
    @Autowired dev.loadpilot.experiments.RunResults finalResults;
    @Autowired dev.loadpilot.experiments.RunMonitorStore monitorQueue;
    @Test void monitorDiscoversPersistedRunsWithoutAnEnqueueOrBrowserRequest() throws Exception {
        // Existing historical test records are excluded only inside this rollback-only test transaction.
        db.update("INSERT INTO experiment_run_monitor(run_id) SELECT id FROM experiment_run ON CONFLICT DO NOTHING");
        db.update("UPDATE experiment_run_monitor SET next_at=clock_timestamp()+INTERVAL '1 day'");
        var run=runs.reserve(runRequest(),"admin");
        assertThat(monitorQueue.read(run.id()).outcome()).isEqualTo("NOT_SCHEDULED");
        var claimed=monitorQueue.claimNext();assertThat(claimed.runId()).isEqualTo(run.id());
        assertThat(monitorQueue.claimNext()).isNull();
        monitorQueue.finish(claimed,dev.loadpilot.experiments.RunMonitorStore.Outcome.WAITING);
        assertThat(monitorQueue.claimNext()).isNull();
        assertThat(monitorQueue.read(run.id()).outcome()).isEqualTo("WAITING");
    }
    void expireRun(UUID id) {
        db.update("UPDATE experiment_run SET created_at=clock_timestamp()-INTERVAL '10 minutes',deadline_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",id);
    }
    void monitorDue(UUID id) {
        db.update("UPDATE experiment_run_monitor SET next_at=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?",id);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void deadlineFailsQueuedRunOnceWithoutDispatchAndDoesNotRewriteUserCancellation() throws Exception {
        var run=runs.reserve(runRequest(),"admin");
        assertThat(runs.enforceDeadline(run.id())).isEqualTo(run);
        expireRun(run.id());var failed=runs.enforceDeadline(run.id());
        assertThat(failed.status()).isEqualTo(RunLifecycle.Status.FAILED);assertThat(failed.workerUid()).isNull();
        assertThat(failed.operations().getFirst().status()).isEqualTo("CANCELLED");
        assertThat(runs.enforceDeadline(run.id())).isEqualTo(failed);assertThat(runs.stop(run.id(),"admin")).isEqualTo(failed);
        assertThat(monitorQueue.read(run.id()).stopReason()).isEqualTo("DEADLINE");
        assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE action='RUN_DEADLINE_EXPIRED' AND object_id=?",Integer.class,run.id())).isEqualTo(1);
        var other=runs.reserve(runRequest(),"admin");runs.stop(other.id(),"admin");expireRun(other.id());
        assertThat(runs.enforceDeadline(other.id()).status()).isEqualTo(RunLifecycle.Status.CANCELLED);
        assertThat(monitorQueue.read(other.id()).stopReason()).isEqualTo("USER");
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void deadlineStopRetainsOwnershipUntilTerminationAndFinishesFailedRatherThanCancelled() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var running=observations.record(run.id(),observation(run,RunLifecycle.WorkerState.RUNNING,false));expireRun(run.id());
        var stopping=runs.enforceDeadline(run.id());assertThat(stopping.status()).isEqualTo(RunLifecycle.Status.STOPPING);
        assertThat(stopping.operations().stream().filter(op->op.kind().equals("STOP")).findFirst().orElseThrow().status()).isEqualTo("PENDING");
        var offline=observations.record(run.id(),observation(stopping,RunLifecycle.WorkerState.UNREACHABLE,false));
        assertThat(offline.status()).isEqualTo(RunLifecycle.Status.STOPPING);assertThat(runs.enforceDeadline(run.id())).isEqualTo(offline);
        var ended=observations.record(run.id(),observation(offline,RunLifecycle.WorkerState.TERMINATED,true));
        assertThat(ended.status()).isEqualTo(RunLifecycle.Status.FAILED);assertThat(ended.verdict()).isEqualTo("UNEVALUATED");
        assertThat(ended.operations().stream().filter(op->op.kind().equals("STOP")).findFirst().orElseThrow().status()).isEqualTo("CONFIRMED");
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_run WHERE target_id=? AND status IN ('QUEUED','SUBMITTING','RUNNING','STOPPING','RECOVERY_REQUIRED')",Integer.class,running.targetId())).isZero();
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void monitorLeaseSerializesFirstClaimAndRecoversAfterCrashWithoutStaleReceiptOverwrite() throws Exception {
        var run=runs.reserve(runRequest(),"admin");
        try {
            dev.loadpilot.experiments.RunMonitorStore.Claim first;
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
                Callable<dev.loadpilot.experiments.RunMonitorStore.Claim> task=()->{ready.countDown();start.await();return monitorQueue.claim(run.id());};
                var a=pool.submit(task);var b=pool.submit(task);assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();start.countDown();
                var values=Arrays.asList(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));assertThat(values.stream().filter(Objects::nonNull).count()).isEqualTo(1);
                first=values.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            }
            assertThat(monitorQueue.read(run.id()).processing()).isTrue();assertThat(monitorQueue.claim(run.id())).isNull();
            db.update("UPDATE experiment_run_monitor SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?",run.id());
            var restarted=new dev.loadpilot.experiments.RunMonitorStore(db,runs);var second=restarted.claim(run.id());assertThat(second.token()).isNotEqualTo(first.token());
            assertThat(restarted.finish(first,dev.loadpilot.experiments.RunMonitorStore.Outcome.RESULT_SAVED)).isFalse();
            assertThat(restarted.finish(second,dev.loadpilot.experiments.RunMonitorStore.Outcome.UNAVAILABLE)).isTrue();
            var status=restarted.read(run.id());assertThat(status.attempts()).isEqualTo(2);assertThat(status.finished()).isFalse();assertThat(status.processing()).isFalse();
            assertThat(java.time.Duration.between(status.checkedAt(),status.nextAt()).toSeconds()).isBetween(14L,16L);
            assertThat(restarted.claim(run.id())).isNull();monitorDue(run.id());
            var third=restarted.claim(run.id());restarted.finish(third,dev.loadpilot.experiments.RunMonitorStore.Outcome.UNAVAILABLE);
            var retry=restarted.read(run.id());assertThat(java.time.Duration.between(retry.checkedAt(),retry.nextAt()).toSeconds()).isBetween(29L,31L);
        } finally {runs.stop(run.id(),"admin");}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void monitorDrivesScopedStopOnceAndConfirmsAbsenceAcrossInstances() throws Exception {
        for(boolean deadline:List.of(false,true)) try(var f=activationFixture(true)) {
            UUID id=f.run().id();
            try {
                f.facade().activate(id,f.plan().sha256());
                var control=new WorkerControlService(runs,plans,f.clients(),workerObserver,workerStops);
                var results=new dev.loadpilot.experiments.WorkerResultService(runs,plans,f.clients(),finalResults,json);
                var monitor=new dev.loadpilot.experiments.RunMonitor(monitorQueue,runs,control,results);
                assertThat(monitor.process(id)).isTrue();assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.RUNNING);
                if(deadline) expireRun(id);else runs.stop(id,"admin");
                monitorDue(id);assertThat(monitor.process(id)).isTrue();
                assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.STOPPING);
                assertThat(f.server().requests.stream().filter(s->s.startsWith("DELETE ")).count()).isEqualTo(1);
                monitorDue(id);
                assertThat(new dev.loadpilot.experiments.RunMonitor(new dev.loadpilot.experiments.RunMonitorStore(db,runs),runs,control,results).process(id)).isTrue();
                assertThat(runs.get(id).status()).isEqualTo(deadline?RunLifecycle.Status.FAILED:RunLifecycle.Status.CANCELLED);
                assertThat(monitorQueue.read(id).stopReason()).isEqualTo(deadline?"DEADLINE":"USER");
                assertThat(f.server().requests.stream().filter(s->s.startsWith("DELETE ")).count()).isEqualTo(1);
                assertThat(monitorQueue.read(id).outcome()).isEqualTo("UNAVAILABLE"); // Deleted Pods have no invented final summary.
                assertThat(finalResults.read(id).resultState()).isEqualTo("NOT_COLLECTED");
                assertThat(runs.get(id).verdict()).isEqualTo("UNEVALUATED");
                assertThat(f.server().requests.stream().filter(s->s.startsWith("PATCH ")).count()).isEqualTo(1); // Only explicit fixture setup activated it.
            } finally {closeObservationFixture(id);}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void monitorPersistsDeadlineBeforeOfflineCallAndNeverResubmitsOrClaimsTermination() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        try {
            var control=org.mockito.Mockito.mock(WorkerControlService.class);var results=org.mockito.Mockito.mock(dev.loadpilot.experiments.WorkerResultService.class);
            org.mockito.Mockito.when(control.observe(run.id())).thenAnswer(call->{
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.STOPPING);
                assertThat(monitorQueue.read(run.id()).stopReason()).isEqualTo("DEADLINE");
                throw new IllegalStateException("private cluster URL token");
            });
            expireRun(run.id());var monitor=new dev.loadpilot.experiments.RunMonitor(monitorQueue,runs,control,results);
            assertThat(monitor.process(run.id())).isTrue();assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.STOPPING);
            assertThat(monitorQueue.read(run.id()).outcome()).isEqualTo("UNAVAILABLE");assertThat(monitorQueue.read(run.id()).finished()).isFalse();
            org.mockito.Mockito.verifyNoInteractions(results);org.mockito.Mockito.verify(control,org.mockito.Mockito.never()).dispatchPendingStop(run.id());
            mvc.perform(get("/api/experiments/runs/"+run.id()+"/monitor")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.monitor.stopReason").value("DEADLINE")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private cluster"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("lease_token"))));
            mvc.perform(get("/api/experiments/runs/"+run.id()+"/monitor").with(anonymous())).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/experiments/runs/"+run.id()+"/monitor").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void finalSummaryCollectedThroughScopedSdkIsImmutableConcurrentAndReadableAfterWorkerApiDisappears() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        dev.loadpilot.experiments.RunResults.Saved saved;
        var protocol=new KubernetesWorkerCollectorTest();
        try(var server=protocol.new Fixture()) {
            var store=org.mockito.Mockito.mock(dev.loadpilot.integrations.ConnectionStore.class);
            org.mockito.Mockito.when(store.view(run.targetId())).thenReturn(new dev.loadpilot.integrations.ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));
            org.mockito.Mockito.when(store.kubernetesMaterial(run.targetId())).thenReturn(new dev.loadpilot.integrations.ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token"));
            String config=json.writeValueAsString(List.of(Map.of("targetId",run.targetId().toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace",executionPolicy().namespace(),"operations",List.of("READ"))));
            var provider=new ExecutionClusterClients(config,json,store);var binding=provider.binding(run.targetId(),executionPolicy().namespace());
            var preview=plans.preview(run.id(),executionPolicy(),executionTarget(),binding);plans.persist(run.id(),preview.sha256(),executionPolicy(),executionTarget(),binding,"admin");
            var planned=plans.workerJob(run);
            server.overrideJob=io.kubernetes.client.openapi.JSON.deserialize(io.kubernetes.client.openapi.JSON.serialize(planned),io.kubernetes.client.openapi.models.V1Job.class);
            server.overrideJob.getMetadata().uid("summary-job").resourceVersion("1");server.overrideJob.getSpec().suspend(false);
            server.overrideJob.status(new io.kubernetes.client.openapi.models.V1JobStatus().conditions(List.of(new io.kubernetes.client.openapi.models.V1JobCondition().type("Complete").status("True"))));
            var now=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
            String message=finalMessage(run,planned).toString();
            var terminated=new io.kubernetes.client.openapi.models.V1ContainerStateTerminated().exitCode(0).startedAt(now.minusSeconds(20)).finishedAt(now).message(message);
            var pod=new io.kubernetes.client.openapi.models.V1Pod().metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().name(run.workerName()+"-pod").namespace(executionPolicy().namespace()).uid("summary-pod")
                    .labels(planned.getMetadata().getLabels()).ownerReferences(List.of(new io.kubernetes.client.openapi.models.V1OwnerReference().apiVersion("batch/v1").controller(true).kind("Job").name(run.workerName()).uid("summary-job"))))
                    .spec(planned.getSpec().getTemplate().getSpec()).status(new io.kubernetes.client.openapi.models.V1PodStatus().phase("Succeeded").containerStatuses(List.of(new io.kubernetes.client.openapi.models.V1ContainerStatus().name("k6").image(executionPolicy().imageDigest()).imageID("sha256:"+"a".repeat(64)).ready(false).restartCount(0).state(new io.kubernetes.client.openapi.models.V1ContainerState().terminated(terminated)))));
            server.overridePods=List.of(pod);
            String resultUrl="/api/experiments/runs/"+run.id()+"/result";
            mvc.perform(get(resultUrl)).andExpect(status().isOk()).andExpect(jsonPath("$.resultState").value("WAITING"));
            var submit=run.operations().stream().filter(op->op.kind().equals("SUBMIT")).findFirst().orElseThrow();
            var service=new dev.loadpilot.experiments.WorkerResultService(runs,plans,provider,finalResults,json);
            var control=new WorkerControlService(runs,plans,provider,workerObserver,workerStops);
            var monitor=new dev.loadpilot.experiments.RunMonitor(monitorQueue,runs,control,service);
            assertThat(monitor.process(run.id())).isTrue();var completed=runs.get(run.id());
            assertThat(completed.status()).isEqualTo(RunLifecycle.Status.COMPLETED);
            assertThat(monitorQueue.read(run.id()).outcome()).isEqualTo("RESULT_SAVED");assertThat(monitorQueue.read(run.id()).finished()).isTrue();
            saved=finalResults.read(run.id()).result();
            assertThat(saved.evidence().summary().httpRequests()).isEqualTo(100);
            assertThat(saved.evidence().scope()).isEqualTo("WHOLE_RUN_INCLUDING_WARMUP_NOT_SLO");
            assertThat(server.requests).hasSize(6).allMatch(value->value.startsWith("GET "));
            org.mockito.Mockito.clearInvocations(store);assertThat(service.collect(run.id())).isEqualTo(saved);
            org.mockito.Mockito.verifyNoInteractions(store);assertThat(server.requests).hasSize(6);
            assertThat(monitor.process(run.id())).isFalse();org.mockito.Mockito.verifyNoInteractions(store);assertThat(server.requests).hasSize(6);
            var collected=new dev.loadpilot.integrations.k6.K6TerminalSummary.Collected("summary-pod",saved.evidence().startedAt(),saved.evidence().finishedAt(),0,saved.evidence().summary());
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var one=pool.submit(()->finalResults.record(run.id(),completed.revision(),"summary-job",collected));
                var two=pool.submit(()->finalResults.record(run.id(),completed.revision(),"summary-job",collected));
                assertThat(one.get(5,TimeUnit.SECONDS)).isEqualTo(saved);assertThat(two.get(5,TimeUnit.SECONDS)).isEqualTo(saved);
            }
            assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_result WHERE run_id=?",Integer.class,run.id())).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE action='WORKER_RESULT_RECORDED' AND object_id=?",Integer.class,run.id())).isEqualTo(1);
            var changed=finalMessage(run,planned).put("httpRequests",101);
            var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),completed.revision(),run.attemptId(),run.shardId(),submit.id(),run.workerName(),run.maximumSeconds(),run.deadlineAt());
            var altered=new dev.loadpilot.integrations.k6.K6TerminalSummary(json).parse(changed.toString(),ticket,planned);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->finalResults.record(run.id(),completed.revision(),"summary-job",new dev.loadpilot.integrations.k6.K6TerminalSummary.Collected("summary-pod",collected.startedAt(),collected.finishedAt(),0,altered)))
                    .hasMessage("This attempt already has different immutable result evidence.");
        }
        String url="/api/experiments/runs/"+run.id()+"/result";
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.verdict").value("UNEVALUATED"))
                .andExpect(jsonPath("$.resultState").value("AVAILABLE")).andExpect(jsonPath("$.result.evidence.summary.httpRequests").value(100))
                .andExpect(jsonPath("$.result.evidence.summary.httpDurationMs.p95").value(300)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fixture-token"))));
        var partial=new dev.loadpilot.experiments.RunPartialResults(db,runs,plans);
        assertThat(new dev.loadpilot.experiments.RunResults(db,runs,plans,json,partial).read(run.id()).result()).isEqualTo(saved);
        mvc.perform(get(url).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->db.update("UPDATE experiment_worker_result SET sha256=? WHERE run_id=?","0".repeat(64),run.id())).hasMessageContaining("Worker results are immutable");
    }
    ObjectNode finalMessage(RunIntents.Run run,io.kubernetes.client.openapi.models.V1Job job) {
        var annotations=job.getMetadata().getAnnotations();var value=json.createObjectNode();
        value.put("schemaVersion",dev.loadpilot.integrations.k6.K6TerminalSummary.SCHEMA).put("runId",run.id().toString()).put("attemptId",run.attemptId().toString()).put("shardId",run.shardId().toString())
                .put("snapshotSha256",annotations.get("loadpilot.dev/snapshot-sha256")).put("scriptSha256",annotations.get("loadpilot.dev/script-sha256"))
                .put("durationMs",20000).put("httpRequests",100).put("k6HttpFailureRate",.02).put("iterations",100).put("droppedIterations",0);
        value.putObject("httpDurationMs").put("min",10).put("max",500).put("avg",100).put("med",90).put("p90",200).put("p95",300).put("p99",450);return value;
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void firstConcurrentFinalWritesAreOneReceiptAndStaleOrWrongWorkerEvidenceCannotCreateIt() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var preview=plans.preview(run.id(),executionPolicy(),executionTarget());plans.persist(run.id(),preview.sha256(),executionPolicy(),executionTarget(),"admin");
        var job=plans.workerJob(run);var completed=observations.record(run.id(),observation(run,RunLifecycle.WorkerState.SUCCEEDED,true));
        var plan=plans.get(run.id());var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),completed.revision(),run.attemptId(),run.shardId(),plan.submitOperationId(),run.workerName(),run.maximumSeconds(),run.deadlineAt());
        var parsed=new dev.loadpilot.integrations.k6.K6TerminalSummary(json).parse(finalMessage(run,job).toString(),ticket,job);
        var now=java.time.Instant.now();var collected=new dev.loadpilot.integrations.k6.K6TerminalSummary.Collected("concurrent-result-pod",now.minusSeconds(20),now,0,parsed);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->finalResults.record(run.id(),run.revision(),completed.workerUid(),collected)).hasMessage("Run changed during final result collection. Reobserve before recording.");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->finalResults.record(run.id(),completed.revision(),"wrong-worker",collected)).hasMessage("A terminal run and confirmed bound worker are required for final results.");
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_result WHERE run_id=?",Integer.class,run.id())).isZero();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<dev.loadpilot.experiments.RunResults.Saved> task=()->{ready.countDown();start.await();return finalResults.record(run.id(),completed.revision(),completed.workerUid(),collected);};
            var one=pool.submit(task);var two=pool.submit(task);assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(one.get(5,TimeUnit.SECONDS)).isEqualTo(two.get(5,TimeUnit.SECONDS));
        }
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_result WHERE run_id=?",Integer.class,run.id())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE action='WORKER_RESULT_RECORDED' AND object_id=?",Integer.class,run.id())).isEqualTo(1);
        assertThat(runs.get(run.id()).verdict()).isEqualTo("UNEVALUATED");
        // Simulate a process dying after result persistence and before acknowledging its monitor lease.
        var abandoned=monitorQueue.claim(run.id());
        db.update("UPDATE experiment_run_monitor SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?",run.id());
        var clients=org.mockito.Mockito.mock(ExecutionClusterClients.class);var control=org.mockito.Mockito.mock(WorkerControlService.class);
        var service=new dev.loadpilot.experiments.WorkerResultService(runs,plans,clients,finalResults,json);
        assertThat(new dev.loadpilot.experiments.RunMonitor(new dev.loadpilot.experiments.RunMonitorStore(db,runs),runs,control,service).process(run.id())).isTrue();
        org.mockito.Mockito.verifyNoInteractions(clients,control);
        assertThat(monitorQueue.read(run.id()).outcome()).isEqualTo("RESULT_SAVED");
        assertThat(monitorQueue.finish(abandoned,dev.loadpilot.experiments.RunMonitorStore.Outcome.UNAVAILABLE)).isFalse();
    }
    @Autowired WorkerObservationService workerObserver;
    @Autowired WorkerStopDispatch workerStops;
    @Autowired dev.loadpilot.experiments.WorkerPreparationDispatch.Receipts preparationReceipts;
    @Autowired dev.loadpilot.experiments.WorkerActivationDispatch.Receipts activationReceipts;
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void runMetricsApiWaitsForWorkerEndsPollingAndRequiresOperator() throws Exception {
        var run=runs.reserve(runRequest(),"admin");String url="/api/experiments/runs/"+run.id()+"/metrics";
        try {
            mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.resources.state").value("WAITING")).andExpect(jsonPath("$.resources.cpuMillicores").isEmpty())
                    .andExpect(jsonPath("$.http.state").value("UNAVAILABLE")).andExpect(jsonPath("$.verdict").value("UNEVALUATED"));
            mvc.perform(get(url).with(anonymous())).andExpect(status().isUnauthorized());
            mvc.perform(get(url).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
            runs.stop(run.id(),"admin");
            mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.resources.state").value("RUN_ENDED")).andExpect(jsonPath("$.pollAfterSeconds").value(0));
        } finally {runs.stop(run.id(),"admin");}
    }
    ExecutionClusterClients clusterFixture(RunIntents.Run run) {
        var clusters=org.mockito.Mockito.mock(ExecutionClusterClients.class);
        org.mockito.Mockito.when(clusters.binding(run.targetId(),executionPolicy().namespace())).thenReturn(new ExecutionClusterClients.Binding(run.targetId(),1,"https://cluster.invalid:6443",List.of("192.0.2.20"),executionPolicy().namespace(),List.of("READ","STOP"),"a".repeat(64)));
        return clusters;
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    record Fixture(UUID target, ScenarioApi.Version script, ObjectNode input, String draftId) {}
    Fixture fixture(boolean approve) throws Exception {
        return fixture(approve,"// Xin chào\nexport default function() {}\n");
    }
    Fixture fixture(boolean approve,String source) throws Exception {
        UUID target = UUID.randomUUID();
        db.update("INSERT INTO registered_target(id,name,base_url,environment,namespace,deployment,container) VALUES (?,?,'https://fixture.invalid','STAGING','fixture','api','api')", target, "Snapshot " + target);
        var script = scenarios.save(null, new ScenarioApi.Input("Snapshot " + UUID.randomUUID(), source, null), "admin");
        if (approve) {
            // Static evidence fixture; actual parser and approval API are covered by their own tests/browser flow.
            var checked = validations.save(script, new ScriptParser.Result(true, true, "static-review-v1", "8.19.0", 24, List.of()), "admin");
            approvals.approve(script.scenarioId(), script.version(), new ScenarioApproval.Input(checked.id(), script.sha256(), true), "admin");
        }
        ObjectNode settings = json.createObjectNode().put("minimumRate", 1).put("maximumRate", 10).put("durationSeconds", 60).put("warmupSeconds", 10)
                .put("p95LatencyMs", 250).put("maximumErrorRatio", 0.01).put("repetitions", 2).put("maximumRuns", 5).put("totalBudgetSeconds", 300);
        ObjectNode input = json.createObjectNode().put("targetId", target.toString()).put("scenarioId", script.scenarioId().toString()).put("scenarioVersion", 1);
        input.set("settings", settings);
        input.set("executionBounds", json.createObjectNode().put("precisionIterationsPerSecond", 1).put("preAllocatedVUs", 1).put("maximumVUs", 2)
                .put("workerCount", 1).put("generatorCpuMillicores", 250).put("generatorMemoryMiB", 128).put("requestTimeoutMs", 1000)
                .put("startupTimeoutSeconds", 5).put("gracefulStopSeconds", 2).put("maximumAttemptsPerRun", 1));
        var profile = json.createObjectNode().put("mode", "ADAPTIVE_LOAD").put("shape", "STEADY").putNull("startRate").put("measurementSeconds", 50);
        profile.putArray("stages"); input.set("loadProfile", profile);
        input.set("sloPolicy", json.createObjectNode().put("latencyPercentile", "P95").put("latencyThresholdMs", 250)
                .put("maximumErrorRatio", 0.01).put("successDefinition", "HTTP_200_399_WITHOUT_TRANSPORT_ERROR"));
        ((ObjectNode) input.get("sloPolicy")).putArray("resourceConditions");
        var created = mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isCreated()).andReturn();
        return new Fixture(target, script, input, json.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }
    ObjectNode review(Fixture fixture) throws Exception {
        return (ObjectNode) json.readTree(mvc.perform(get("/api/experiments/drafts/" + fixture.draftId() + "/review")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    ObjectNode finalizeRequest(ObjectNode preview) { return json.createObjectNode().put("revision", preview.get("inputs").get("draftRevision").asLong()).put("reviewHash", preview.get("sha256").asText()).put("acknowledged", true); }
    String finalize(Fixture fixture, ObjectNode request) throws Exception {
        return json.readTree(mvc.perform(post("/api/experiments/drafts/" + fixture.draftId() + "/finalize").with(csrf()).contentType("application/json").content(request.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runtimeCheckRequired").value(true)).andExpect(jsonPath("$.executionAvailable").value(false)).andReturn().getResponse().getContentAsString()).get("id").asText();
    }
    @Test void immutableInputsAndIdempotentRetrySurviveDraftTargetAndScriptEdits() throws Exception {
        var fixture = fixture(true); var preview = review(fixture); var request = finalizeRequest(preview);
        assertThat(review(fixture).get("sha256").asText()).isEqualTo(preview.get("sha256").asText());
        String id = finalize(fixture, request);
        db.update("UPDATE registered_target SET name='Changed system',base_url='https://changed.invalid',revision=revision+1 WHERE id=?", fixture.target());
        fixture.input().put("revision", 1); ((ObjectNode) fixture.input().get("settings")).put("maximumRate", 20);
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isOk());
        scenarios.save(fixture.script().scenarioId(), new ScenarioApi.Input(fixture.script().name(), "// changed\nexport default function() {}", 1), "admin");
        assertThat(finalize(fixture, request)).isEqualTo(id);
        mvc.perform(get("/api/experiments/snapshots/" + id)).andExpect(jsonPath("$.inputs.target.baseUrl").value("https://fixture.invalid"))
                .andExpect(jsonPath("$.inputs.settings.maximumRate").value(10)).andExpect(jsonPath("$.inputs.scenario.script.source").value(fixture.script().source()))
                .andExpect(jsonPath("$.inputs.measurementSeconds").value(50)).andExpect(jsonPath("$.sha256").value(preview.get("sha256").asText()));
        mvc.perform(put("/api/experiments/snapshots/" + id).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());
        assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE object_id=?::uuid AND action='EXPERIMENT_INPUTS_FINALIZED'", Integer.class, id)).isEqualTo(1);
    }
    @Test void rejectsChangedTargetHashDraftRevisionAndMissingAcknowledgement() throws Exception {
        var fixture = fixture(true); var request = finalizeRequest(review(fixture)); request.put("acknowledged", false);
        mvc.perform(post("/api/experiments/drafts/" + fixture.draftId() + "/finalize").with(csrf()).contentType("application/json").content(request.toString())).andExpect(status().isBadRequest());
        request.put("acknowledged", true).put("revision", 2);
        mvc.perform(post("/api/experiments/drafts/" + fixture.draftId() + "/finalize").with(csrf()).contentType("application/json").content(request.toString())).andExpect(status().isConflict());
        request.put("revision", 1);
        db.update("UPDATE registered_target SET revision=revision+1 WHERE id=?", fixture.target());
        mvc.perform(post("/api/experiments/drafts/" + fixture.draftId() + "/finalize").with(csrf()).contentType("application/json").content(request.toString())).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_input_snapshot WHERE draft_id=?::uuid", Integer.class, fixture.draftId())).isZero();
    }
    @Test void rejectsUnapprovedMissingScriptAndArchivedTarget() throws Exception {
        var unapproved = fixture(false);
        mvc.perform(get("/api/experiments/drafts/" + unapproved.draftId() + "/review")).andExpect(status().isConflict());
        unapproved.input().putNull("scenarioId").putNull("scenarioVersion").put("revision", 1);
        mvc.perform(put("/api/experiments/drafts/" + unapproved.draftId()).with(csrf()).contentType("application/json").content(unapproved.input().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/experiments/drafts/" + unapproved.draftId() + "/review")).andExpect(status().isBadRequest());
        var approved = fixture(true); db.update("UPDATE registered_target SET archived_at=CURRENT_TIMESTAMP WHERE id=?", approved.target());
        mvc.perform(get("/api/experiments/drafts/" + approved.draftId() + "/review")).andExpect(status().isConflict());
    }
    @Test void scalarFractionsAccessAndBodyBoundsCannotBypassValidation() throws Exception {
        var fixture = fixture(true); ((ObjectNode) fixture.input().get("settings")).put("durationSeconds", 60.9);
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/experiments/drafts/" + fixture.draftId() + "/review").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/experiments/drafts/" + fixture.draftId() + "/finalize").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content("x".repeat(32769))).andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/api/experiments/snapshots/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
    @Test void detectsStoredSnapshotCorruption() throws Exception {
        var fixture = fixture(true); String id = finalize(fixture, finalizeRequest(review(fixture)));
        db.update("UPDATE experiment_input_snapshot SET inputs_json=inputs_json || ' ' WHERE id=?::uuid", id);
        mvc.perform(get("/api/experiments/snapshots/" + id)).andExpect(status().isServiceUnavailable());
    }
    @Test void legacySnapshotInputsRemainUnexpandedAndLegacyDraftNeedsExplicitBounds() throws Exception {
        var fixture = fixture(true);
        var legacy = (ObjectNode) review(fixture).get("inputs").deepCopy();
        legacy.put("schemaVersion", "experiment-input-v1");
        legacy.remove(List.of("executionBounds", "maximumAttemptSeconds", "reservedAttemptsPerLoad", "loadProfile", "measurementRate", "sloPolicy"));
        String bytes = json.writeValueAsString(legacy);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.getBytes(StandardCharsets.UTF_8)));
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO experiment_input_snapshot(id,draft_id,draft_revision,sha256,inputs_json,created_by) VALUES (?,?::uuid,1,?,?,'admin')", id, fixture.draftId(), hash, bytes);
        var loaded = mvc.perform(get("/api/experiments/snapshots/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs.executionBounds").doesNotExist()).andExpect(jsonPath("$.sha256").value(hash)).andReturn();
        assertThat(json.readTree(loaded.getResponse().getContentAsString()).get("inputs")).isEqualTo(legacy);
        fixture.input().remove("executionBounds"); fixture.input().put("revision", 1);
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/experiments/drafts/" + fixture.draftId() + "/review")).andExpect(status().isBadRequest());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentFinalizationReturnsOneDurableSnapshot() throws Exception {
        var fixture = fixture(true); var preview = review(fixture);
        var request = new ExperimentSnapshots.Finalize(1L, preview.get("sha256").asText(), true);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            Callable<ExperimentSnapshots.Snapshot> task = () -> { ready.countDown(); start.await(); return snapshots.finalizeInputs(UUID.fromString(fixture.draftId()), request, "admin"); };
            var first = pool.submit(task); var second = pool.submit(task);
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).id()).isEqualTo(second.get(5, TimeUnit.SECONDS).id());
            assertThat(db.queryForObject("SELECT count(*) FROM experiment_input_snapshot WHERE draft_id=?::uuid", Integer.class, fixture.draftId())).isEqualTo(1);
        }
    }
    @Test void sloChoicesPersistFreezeAndRejectIncompleteConditions() throws Exception {
        var fixture = fixture(true);
        var policy = (ObjectNode) fixture.input().get("sloPolicy");
        policy.put("latencyPercentile", "P99").put("throughputKind", "SUCCESSFUL").put("minimumThroughputRps", 25.5).put("throughputOperator", "GE");
        policy.withArray("resourceConditions").addObject().put("metric", "CPU_USAGE_MILLICORES").put("threshold", 250.5)
                .put("scope", "SELECTED_CONTAINER_ALL_DEPLOYMENT_PODS").put("aggregation", "MAXIMUM_OF_POD_SUM_OVER_MEASUREMENT_WINDOW").put("operator", "LE");
        fixture.input().put("revision", 1);
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/experiments/drafts/" + fixture.draftId())).andExpect(jsonPath("$.sloPolicy.latencyPercentile").value("P99"));
        var preview = review(fixture); String id = finalize(fixture, finalizeRequest(preview));
        fixture.input().put("revision", 2); policy.put("latencyPercentile", "P90").put("throughputKind", "TOTAL");
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/experiments/snapshots/" + id)).andExpect(jsonPath("$.inputs.sloPolicy.latencyPercentile").value("P99"))
                .andExpect(jsonPath("$.inputs.sloPolicy.throughputKind").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.inputs.sloPolicy.resourceConditions[0].threshold").value(250.5))
                .andExpect(jsonPath("$.inputs.sloPolicy.minimumThroughputRps").value(25.5));
        fixture.input().put("revision", 3); policy.remove("throughputOperator");
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isBadRequest());
        policy.put("throughputOperator", "GE").put("minimumThroughputRps", 0);
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isBadRequest());
        policy.put("minimumThroughputRps", 25.5); policy.remove("resourceConditions");
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/experiments/drafts/" + fixture.draftId() + "/review")).andExpect(status().isBadRequest());
        fixture.input().put("revision", 4);
        fixture.input().remove("sloPolicy");
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/experiments/drafts/" + fixture.draftId() + "/review")).andExpect(status().isBadRequest());
    }
    @Test void spikeProfileFreezesPeakWindowAndRejectsRecoveryWindow() throws Exception {
        var fixture = fixture(true);
        ((ObjectNode) fixture.input().get("settings")).put("durationSeconds", 20).put("warmupSeconds", 2);
        ((ObjectNode) fixture.input().get("executionBounds")).putNull("precisionIterationsPerSecond");
        var profile = json.createObjectNode().put("mode", "SINGLE_RUN").put("shape", "SPIKE").put("startRate", 1).put("measurementSeconds", 8);
        var stages = profile.putArray("stages");
        for (int[] stage : new int[][]{{2, 5}, {8, 5}, {2, 1}, {8, 1}}) stages.addObject().put("durationSeconds", stage[0]).put("targetRate", stage[1]);
        fixture.input().set("loadProfile", profile); fixture.input().put("revision", 1);
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isOk());
        var preview = review(fixture);
        assertThat(preview.get("inputs").get("measurementRate").asInt()).isEqualTo(5);
        String id = finalize(fixture, finalizeRequest(preview));
        mvc.perform(get("/api/experiments/snapshots/" + id)).andExpect(jsonPath("$.inputs.schemaVersion").value("experiment-input-v5"))
                .andExpect(jsonPath("$.inputs.measurementSeconds").value(8)).andExpect(jsonPath("$.inputs.executor").value("ramping-arrival-rate"))
                .andExpect(jsonPath("$.inputs.loadProfile.stages.length()").value(4));
        fixture.input().put("revision", 2); ((ObjectNode) fixture.input().get("settings")).put("warmupSeconds", 12);
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isBadRequest());
        ((ObjectNode) profile.get("stages").get(0)).put("targetRate", 5.5);
        mvc.perform(put("/api/experiments/drafts/" + fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isBadRequest());
    }
    RunIntents.Start runRequest() throws Exception {
        return runRequest("// Xin chào\nexport default function() {}\n");
    }
    RunIntents.Start runRequest(String source) throws Exception {
        return runRequest(source,5);
    }
    RunIntents.Start runRequest(String source,int startupSeconds) throws Exception {
        var fixture=fixture(true,source);
        ((ObjectNode)fixture.input().get("executionBounds")).put("startupTimeoutSeconds",startupSeconds);
        var profile=json.createObjectNode().put("mode","SINGLE_RUN").put("shape","STEADY").put("startRate",5).put("measurementSeconds",50);
        profile.putArray("stages").addObject().put("durationSeconds",60).put("targetRate",5);
        fixture.input().set("loadProfile",profile);fixture.input().put("revision",1);
        mvc.perform(put("/api/experiments/drafts/"+fixture.draftId()).with(csrf()).contentType("application/json").content(fixture.input().toString())).andExpect(status().isOk());
        var preview=review(fixture);String id=finalize(fixture,finalizeRequest(preview));
        return new RunIntents.Start(UUID.fromString(id),UUID.randomUUID(),preview.get("sha256").asText(),true);
    }
    @Test void reservesLinkedIntentOnceAndCancelsQueuedRunWithoutExternalStop() throws Exception {
        var request=runRequest();var run=runs.reserve(request,"admin");
        assertThat(runs.reserve(request,"admin").id()).isEqualTo(run.id());
        assertThat(run.status()).isEqualTo(RunLifecycle.Status.QUEUED);
        assertThat(run.maximumSeconds()).isEqualTo(67);
        assertThat(run.workerName()).isEqualTo("lp-"+run.attemptId());
        assertThat(run.verdict()).isEqualTo("UNEVALUATED");
        assertThat(run.operations()).hasSize(1);
        assertThat(run.operations().getFirst().status()).isEqualTo("PENDING");
        var stopped=runs.stop(run.id(),"admin");
        assertThat(stopped.status()).isEqualTo(RunLifecycle.Status.CANCELLED);
        assertThat(stopped.operations().getFirst().status()).isEqualTo("CANCELLED");
        assertThat(runs.stop(run.id(),"admin").revision()).isEqualTo(stopped.revision());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runs.claimSubmission(run.id())).hasMessage("This run cannot be submitted.");
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void submissionIntentAndStopRemainUnconfirmedAndPublicStartIsGated() throws Exception {
        var request=runRequest();var run=runs.reserve(request,"admin");
        try {
        var claimed=runs.claimSubmission(run.id());
        assertThat(claimed.status()).isEqualTo(RunLifecycle.Status.SUBMITTING);
        var stopping=runs.stop(run.id(),"admin");
        assertThat(stopping.status()).isEqualTo(RunLifecycle.Status.STOPPING);
        assertThat(stopping.operations()).extracting(RunIntents.Operation::kind).containsExactlyInAnyOrder("SUBMIT","STOP");
        assertThat(stopping.operations()).allMatch(op->op.status().equals("PENDING"));
        assertThat(runs.stop(run.id(),"admin").revision()).isEqualTo(stopping.revision());
        mvc.perform(post("/api/experiments/runs").with(csrf()).contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(run.id().toString())).andExpect(jsonPath("$.status").value("STOPPING"));
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_start_workflow WHERE run_id=?",Integer.class,run.id())).isZero(); // Replay cannot adopt legacy/internal work.
        var notReserved=runRequest();
        mvc.perform(post("/api/experiments/runs").with(csrf()).contentType("application/json").content(json.writeValueAsString(notReserved)))
                .andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_run WHERE snapshot_id=?",Integer.class,notReserved.snapshotId())).isZero();
        mvc.perform(get("/api/experiments/runs/"+run.id())).andExpect(status().isOk()).andExpect(jsonPath("$.verdict").value("UNEVALUATED"));
        mvc.perform(post("/api/experiments/runs/"+run.id()+"/stop")).andExpect(status().isForbidden());
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentStartReservationsProduceOneRunAttemptAndSubmitIntent() throws Exception {
        var request=runRequest();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<RunIntents.Run> task=()->{ready.countDown();start.await();return runs.reserve(request,"admin");};
            var one=pool.submit(task);var two=pool.submit(task);
            assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();start.countDown();
            var result=one.get(5,TimeUnit.SECONDS);assertThat(two.get(5,TimeUnit.SECONDS).id()).isEqualTo(result.id());
            assertThat(db.queryForObject("SELECT count(*) FROM experiment_attempt WHERE run_id=?",Integer.class,result.id())).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM experiment_operation WHERE attempt_id=?",Integer.class,result.attemptId())).isEqualTo(1);
            runs.stop(result.id(),"admin");
        }
    }
    @Test void rejectsHashKeyAndTargetDriftWithoutCreatingAnotherRun() throws Exception {
        var request=runRequest();var run=runs.reserve(request,"admin");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runs.reserve(new RunIntents.Start(request.snapshotId(),request.requestKey(),"0".repeat(64),true),"admin"))
                .hasMessage("Snapshot hash does not match the reviewed inputs.");
        var other=runRequest();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runs.reserve(new RunIntents.Start(other.snapshotId(),request.requestKey(),other.snapshotHash(),true),"admin"))
                .hasMessage("This start key belongs to different inputs.");
        db.update("UPDATE registered_target SET revision=revision+1 WHERE id=?",run.targetId());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runs.claimSubmission(run.id())).hasMessage("Target changed after review. Review a new snapshot.");
        assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.QUEUED);
    }
    @Test void rejectsInsufficientRemainingBudgetBeforeSubmission() throws Exception {
        var request=runRequest();var run=runs.reserve(request,"admin");
        db.update("UPDATE experiment_run SET deadline_at=clock_timestamp()+INTERVAL '1 second' WHERE id=?",run.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runs.claimSubmission(run.id())).hasMessage("Run budget expired before submission.");
        assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.QUEUED);
        assertThat(runs.stop(run.id(),"admin").status()).isEqualTo(RunLifecycle.Status.CANCELLED);
    }
    @Test void refusesCompetingSnapshotAndNewKeyForAlreadyUsedSnapshot() throws Exception {
        var request=runRequest();var run=runs.reserve(request,"admin");
        var snapshot=snapshots.get(request.snapshotId());
        db.update("UPDATE experiment_draft SET revision=revision+1 WHERE id=?",snapshot.inputs().draftId());
        var review=snapshots.preview(snapshot.inputs().draftId());
        var other=snapshots.finalizeInputs(snapshot.inputs().draftId(),new ExperimentSnapshots.Finalize(review.inputs().draftRevision(),review.sha256(),true),"admin");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runs.reserve(new RunIntents.Start(other.id(),UUID.randomUUID(),other.sha256(),true),"admin"))
                .hasMessage("This target already has an active or unresolved run.");
        runs.stop(run.id(),"admin");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runs.reserve(new RunIntents.Start(request.snapshotId(),UUID.randomUUID(),request.snapshotHash(),true),"admin"))
                .hasMessage("This snapshot already has a run. Retry its original start key or review a new draft revision.");
    }
    RunObservations.Observation observation(RunIntents.Run run,RunLifecycle.WorkerState state,boolean terminated) {
        return new RunObservations.Observation(UUID.randomUUID(),run.attemptId(),run.operations().stream().filter(op->op.kind().equals("SUBMIT")).findFirst().orElseThrow().id(),
                run.revision(),run.workerName(),run.workerUid()==null?"fixture-worker-uid":run.workerUid(),state,terminated);
    }
    @Test void completionRequiresTerminationAndRemainsUnevaluatedWithIdempotentEvidence() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var finishedButLive=observations.record(run.id(),observation(run,RunLifecycle.WorkerState.SUCCEEDED,false));
        assertThat(finishedButLive.status()).isEqualTo(RunLifecycle.Status.RUNNING);
        var proof=observation(finishedButLive,RunLifecycle.WorkerState.SUCCEEDED,true);
        var finished=observations.record(run.id(),proof);
        assertThat(finished.status()).isEqualTo(RunLifecycle.Status.COMPLETED);
        assertThat(finished.workerUid()).isEqualTo("fixture-worker-uid");
        assertThat(finished.verdict()).isEqualTo("UNEVALUATED");
        assertThat(observations.record(run.id(),proof).revision()).isEqualTo(finished.revision());
        assertThat(runs.stop(run.id(),"admin").status()).isEqualTo(RunLifecycle.Status.COMPLETED);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->observations.record(run.id(),observation(finished,RunLifecycle.WorkerState.RUNNING,false)))
                .hasMessage("This run cannot accept worker observations.");
    }
    @Test void delayedRunningCannotUndoStopAndCancellationWaitsForWorkerTermination() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var delayed=observation(run,RunLifecycle.WorkerState.RUNNING,false);
        var stopping=runs.stop(run.id(),"admin");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->observations.record(run.id(),delayed))
                .hasMessage("Run changed after the observation began. Reobserve the worker.");
        var stillStopping=observations.record(run.id(),observation(stopping,RunLifecycle.WorkerState.RUNNING,false));
        assertThat(stillStopping.status()).isEqualTo(RunLifecycle.Status.STOPPING);
        var cancelled=observations.record(run.id(),observation(stillStopping,RunLifecycle.WorkerState.TERMINATED,true));
        assertThat(cancelled.status()).isEqualTo(RunLifecycle.Status.CANCELLED);
        assertThat(cancelled.operations()).allMatch(op->op.status().equals("CONFIRMED"));
    }
    @Test void outagePreservesProtectionAndWrongWorkerCannotReleaseIt() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var running=observations.record(run.id(),observation(run,RunLifecycle.WorkerState.RUNNING,false));
        var offline=observations.record(run.id(),observation(running,RunLifecycle.WorkerState.UNREACHABLE,false));
        assertThat(offline.status()).isEqualTo(RunLifecycle.Status.RECOVERY_REQUIRED);
        assertThat(offline.verdict()).isEqualTo("UNEVALUATED");
        var proof=observation(offline,RunLifecycle.WorkerState.SUCCEEDED,true);
        var wrong=new RunObservations.Observation(proof.id(),proof.attemptId(),proof.submitOperationId(),proof.expectedRevision(),proof.workerName(),"replaced-worker",proof.state(),true);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->observations.record(run.id(),wrong))
                .hasMessage("Worker UID changed. Keep recovery protection and reconcile the worker identity.");
        assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.RECOVERY_REQUIRED);
        var recovered=observations.record(run.id(),proof);
        assertThat(recovered.status()).isEqualTo(RunLifecycle.Status.COMPLETED);
    }
    @Test void wrongIntentAndInconsistentTerminationAreRejectedWithoutEvidenceWrites() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var proof=observation(run,RunLifecycle.WorkerState.RUNNING,false);
        var wrong=new RunObservations.Observation(proof.id(),proof.attemptId(),UUID.randomUUID(),proof.expectedRevision(),proof.workerName(),proof.workerUid(),proof.state(),false);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->observations.record(run.id(),wrong))
                .hasMessage("Observation does not match this attempt and submission intent.");
        var impossible=new RunObservations.Observation(proof.id(),proof.attemptId(),proof.submitOperationId(),proof.expectedRevision(),proof.workerName(),proof.workerUid(),proof.state(),true);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->observations.record(run.id(),impossible))
                .hasMessage("Worker identity and termination evidence are inconsistent.");
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_observation WHERE run_id=?",Integer.class,run.id())).isZero();
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void dispatchTicketReturnsAfterCommitAndRejectsAnAmbientTransaction() throws Exception {
        var run=runs.reserve(runRequest(),"admin");
        var ticket=dispatch.prepare(run.id());
        // With no surrounding test transaction this query uses another connection after prepare returns.
        assertThat(db.queryForObject("SELECT status FROM experiment_run WHERE id=?",String.class,run.id())).isEqualTo("SUBMITTING");
        assertThat(ticket.attemptId()).isEqualTo(run.attemptId());
        assertThat(ticket.submitOperationId()).isEqualTo(run.operations().getFirst().id());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status->dispatch.prepare(run.id())))
                .hasMessage("Dispatch preparation must begin outside an existing transaction.");
        var stopping=runs.stop(run.id(),"admin");
        observations.record(run.id(),observation(stopping,RunLifecycle.WorkerState.TERMINATED,true));
    }
    @Test void executionFailureIsNotSloFailureAndObservationIdsCannotChangeEvidence() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var proof=observation(run,RunLifecycle.WorkerState.FAILED,true);
        var failed=observations.record(run.id(),proof);
        assertThat(failed.status()).isEqualTo(RunLifecycle.Status.FAILED);
        assertThat(failed.verdict()).isEqualTo("UNEVALUATED");
        var changed=new RunObservations.Observation(proof.id(),proof.attemptId(),proof.submitOperationId(),proof.expectedRevision(),proof.workerName(),proof.workerUid(),RunLifecycle.WorkerState.SUCCEEDED,true);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->observations.record(run.id(),changed))
                .hasMessage("Observation identity was reused for different evidence.");
        assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.FAILED);
    }
    K6JobPlan.Policy executionPolicy() { return new K6JobPlan.Policy("loadpilot-runners","example.invalid/k6@sha256:"+"a".repeat(64),"loadpilot-runner","sandboxed",32); }
    K6JobPlan.PinnedTarget executionTarget() { return new K6JobPlan.PinnedTarget("fixture.invalid","192.0.2.10",443); }
    @Test void executionPlanPersistsExactBytesAndRejectsImageDestinationAndStopDrift() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());var policy=executionPolicy();var target=executionTarget();
        var preview=plans.preview(run.id(),policy,target);var saved=plans.persist(run.id(),preview.sha256(),policy,target,"admin");
        assertThat(saved.canonicalJson()).isEqualTo(preview.canonicalJson());
        assertThat(plans.get(run.id())).isEqualTo(saved);
        assertThat(plans.verifyCurrent(run.id(),saved.sha256(),policy,target)).isEqualTo(saved);
        var changed=new K6JobPlan.Policy(policy.namespace(),"example.invalid/k6@sha256:"+"b".repeat(64),policy.serviceAccount(),policy.runtimeClass(),32);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->plans.verifyCurrent(run.id(),saved.sha256(),changed,target))
                .hasMessage("Saved execution plan differs from current dispatch inputs.");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->plans.verifyCurrent(run.id(),saved.sha256(),policy,new K6JobPlan.PinnedTarget(target.host(),"192.0.2.11",443)))
                .hasMessage("Saved execution plan differs from current dispatch inputs.");
        runs.stop(run.id(),"admin");
        assertThat(plans.persist(run.id(),saved.sha256(),changed,target,"admin").id()).isEqualTo(saved.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->plans.verifyCurrent(run.id(),saved.sha256(),policy,target))
                .hasMessage("This run cannot prepare or dispatch a worker plan.");
    }
    @Test void earlierUnsuspendedPlanRemainsReadableButCannotUseTheNewPreparationReview() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var preview=plans.preview(run.id(),executionPolicy(),executionTarget());
        plans.persist(run.id(),preview.sha256(),executionPolicy(),executionTarget(),"admin");
        // Emulate a historical planner-v1 row in this rollback-only fixture; never migrate stored plan bytes.
        var historical=(ObjectNode)json.readTree(preview.canonicalJson());historical.put("plannerVersion","job-planner-v1");
        ((ObjectNode)historical.path("job").path("spec")).remove("suspend");
        String original=json.writeValueAsString(historical),hash=dev.loadpilot.integrations.k6.K6PlanEnvelope.hash(original);
        db.update("UPDATE experiment_execution_plan SET plan_json=?,sha256=? WHERE run_id=?",original,hash,run.id());
        assertThat(plans.get(run.id()).canonicalJson()).isEqualTo(original);
        assertThat(plans.workerJob(run).getSpec().getSuspend()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->plans.verifyCurrent(run.id(),hash,executionPolicy(),executionTarget()))
                .hasMessage("Saved execution plan differs from current dispatch inputs.");
        assertThat(plans.persist(run.id(),hash,executionPolicy(),executionTarget(),"admin").canonicalJson()).isEqualTo(original);
        assertThat(plans.get(run.id()).sha256()).isEqualTo(hash);
    }
    @Test void planReviewRejectsChangedPolicyAndSavedBytesOrMetadataCorruption() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());var policy=executionPolicy();var target=executionTarget();
        var preview=plans.preview(run.id(),policy,target);
        var changed=new K6JobPlan.Policy(policy.namespace(),policy.imageDigest(),policy.serviceAccount(),policy.runtimeClass(),64);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->plans.persist(run.id(),preview.sha256(),changed,target,"admin"))
                .hasMessage("Execution policy, destination or run changed after plan review.");
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_execution_plan WHERE run_id=?",Integer.class,run.id())).isZero();
        plans.persist(run.id(),preview.sha256(),policy,target,"admin");
        db.update("UPDATE experiment_execution_plan SET plan_json=plan_json || ' ' WHERE run_id=?",run.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->plans.get(run.id())).hasMessage("Stored execution plan integrity verification failed.");
        db.update("UPDATE experiment_execution_plan SET plan_json=?,run_revision=run_revision+1 WHERE run_id=?",preview.canonicalJson(),run.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->plans.get(run.id())).hasMessage("Stored execution plan identity verification failed.");
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentPlanPersistenceReturnsOneImmutableRecordAndAudit() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());var policy=executionPolicy();var target=executionTarget();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var preview=plans.preview(run.id(),policy,target);
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<ExecutionPlans.Saved> task=()->{ready.countDown();start.await();return plans.persist(run.id(),preview.sha256(),policy,target,"admin");};
            var one=pool.submit(task);var two=pool.submit(task);assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();start.countDown();
            var saved=one.get(5,TimeUnit.SECONDS);assertThat(two.get(5,TimeUnit.SECONDS).id()).isEqualTo(saved.id());
            assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE object_id=? AND action='EXECUTION_PLAN_PREPARED'",Integer.class,run.id())).isEqualTo(1);
        } finally {
            var stopping=runs.stop(run.id(),"admin");observations.record(run.id(),observation(stopping,RunLifecycle.WorkerState.TERMINATED,true));
        }
    }
    @Test void executionPlanRechecksTargetRevisionBeforeDispatch() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());var policy=executionPolicy();var target=executionTarget();
        var preview=plans.preview(run.id(),policy,target);var saved=plans.persist(run.id(),preview.sha256(),policy,target,"admin");
        db.update("UPDATE registered_target SET revision=revision+1 WHERE id=?",run.targetId());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->plans.verifyCurrent(run.id(),saved.sha256(),policy,target))
                .hasMessage("Target changed after review. Review a new snapshot.");
        assertThat(plans.get(run.id()).canonicalJson()).isEqualTo(saved.canonicalJson());
    }
    @Test void defaultExecutionPreflightIsDisabledWithoutInheritingConnectionPrivileges() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->preflight.prepare(run.id()))
                .isInstanceOfSatisfying(ExecutionRuntimePolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_RUNTIME_UNCONFIGURED"));
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_execution_plan WHERE run_id=?",Integer.class,run.id())).isZero();
    }
    @Test void destinationPreflightRejectsRebindingBeforePlanPersistenceAndDispatchVerification() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var address=new java.util.concurrent.atomic.AtomicReference<>("192.0.2.10");
        try(var destinations=new ExecutionDestinationPolicy(Map.of("https://fixture.invalid",List.of("192.0.2.10","192.0.2.11")),
                host->List.of(java.net.InetAddress.getByName(address.get())),java.time.Duration.ofSeconds(2))) {
            var gate=new ExecutionPreflight(runs,snapshots,plans,destinations,new ExecutionRuntimePolicy(executionPolicy()),clusterFixture(run),sourcePolicy);var candidate=gate.prepare(run.id());
            address.set("192.0.2.11");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->gate.persist(run.id(),candidate.plan().sha256(),candidate.destination(),"admin"))
                    .isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_DESTINATION_CHANGED"));
            assertThat(db.queryForObject("SELECT count(*) FROM experiment_execution_plan WHERE run_id=?",Integer.class,run.id())).isZero();
            address.set("192.0.2.10");var saved=gate.persist(run.id(),candidate.plan().sha256(),candidate.destination(),"admin");
            assertThat(gate.verifySaved(run.id(),saved.sha256(),candidate.destination()).id()).isEqualTo(saved.id());
            address.set("169.254.169.254");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->gate.verifySaved(run.id(),saved.sha256(),candidate.destination()))
                    .isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_DESTINATION_DENIED"));
            assertThat(plans.get(run.id()).sha256()).isEqualTo(saved.sha256());
        }
    }
    @Test void changedOperatorRuntimeCannotReuseReviewedOrPersistedPlan() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        try(var destinations=new ExecutionDestinationPolicy(Map.of("https://fixture.invalid",List.of("192.0.2.10")),
                host->List.of(java.net.InetAddress.getByName("192.0.2.10")),java.time.Duration.ofSeconds(2))) {
            var original=executionPolicy();
            var changed=new K6JobPlan.Policy(original.namespace(),"example.invalid/k6@sha256:"+"b".repeat(64),original.serviceAccount(),original.runtimeClass(),32);
            var first=new ExecutionPreflight(runs,snapshots,plans,destinations,new ExecutionRuntimePolicy(original),clusterFixture(run),sourcePolicy);
            var second=new ExecutionPreflight(runs,snapshots,plans,destinations,new ExecutionRuntimePolicy(changed),clusterFixture(run),sourcePolicy);
            var reviewed=first.prepare(run.id());
            org.assertj.core.api.Assertions.assertThatThrownBy(()->second.persist(run.id(),reviewed.plan().sha256(),reviewed.destination(),"admin"))
                    .hasMessage("Execution policy, destination or run changed after plan review.");
            var saved=first.persist(run.id(),reviewed.plan().sha256(),reviewed.destination(),"admin");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->second.verifySaved(run.id(),saved.sha256(),reviewed.destination()))
                    .hasMessage("Saved execution plan differs from current dispatch inputs.");
            assertThat(plans.get(run.id()).canonicalJson()).isEqualTo(saved.canonicalJson());
        }
    }
    RunIntents.Run observationFixture() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var preview=plans.preview(run.id(),executionPolicy(),executionTarget());plans.persist(run.id(),preview.sha256(),executionPolicy(),executionTarget(),"admin");return run;
    }
    void closeObservationFixture(UUID id) {
        var current=runs.stop(id,"admin");if(!current.status().terminal()) observations.record(id,observation(current,RunLifecycle.WorkerState.TERMINATED,true));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void observationServicePersistsEvidenceWithoutHoldingTransactionDuringCollection() throws Exception {
        var run=observationFixture();var collector=org.mockito.Mockito.mock(KubernetesWorkerCollector.class);
        try {
            org.mockito.Mockito.when(collector.collect(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull())).thenAnswer(call->{
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                io.kubernetes.client.openapi.models.V1Job expected=call.getArgument(1);assertThat(expected.getMetadata().getName()).isEqualTo(run.workerName());
                return observation(run,RunLifecycle.WorkerState.SUCCEEDED,true);
            });
            var finished=workerObserver.observe(run.id(),collector);assertThat(finished.status()).isEqualTo(RunLifecycle.Status.COMPLETED);assertThat(finished.verdict()).isEqualTo("UNEVALUATED");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->workerObserver.observe(run.id(),collector)).hasMessage("This run cannot collect worker evidence.");
            org.mockito.Mockito.verify(collector,org.mockito.Mockito.times(1)).collect(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull());
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void lateCollectorResponseCannotOverwriteConcurrentStop() throws Exception {
        var run=observationFixture();var collector=org.mockito.Mockito.mock(KubernetesWorkerCollector.class);
        try {
            org.mockito.Mockito.when(collector.collect(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull())).thenAnswer(call->{runs.stop(run.id(),"admin");return observation(run,RunLifecycle.WorkerState.RUNNING,false);});
            org.assertj.core.api.Assertions.assertThatThrownBy(()->workerObserver.observe(run.id(),collector)).hasMessage("Run changed after the observation began. Reobserve the worker.");
            assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.STOPPING);
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void collectorAbsenceRetainsRecoveryProtectionAndNeverCompletesRun() throws Exception {
        var run=observationFixture();var collector=org.mockito.Mockito.mock(KubernetesWorkerCollector.class);
        try {
            org.mockito.Mockito.when(collector.collect(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull())).thenThrow(new KubernetesWorkerCollector.Problem(404,"not found"));
            var unknown=workerObserver.observe(run.id(),collector);assertThat(unknown.status()).isEqualTo(RunLifecycle.Status.RECOVERY_REQUIRED);assertThat(unknown.verdict()).isEqualTo("UNEVALUATED");
            assertThat(unknown.operations().getFirst().status()).isEqualTo("PENDING");
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void stoppedKnownWorkerCanConfirmAbsenceButUncertainSubmissionCannot() throws Exception {
        var run=observationFixture();var collector=org.mockito.Mockito.mock(KubernetesWorkerCollector.class);
        try {
            var known=observations.record(run.id(),observation(run,RunLifecycle.WorkerState.RUNNING,false));
            var stopping=runs.stop(known.id(),"admin");
            org.mockito.Mockito.when(collector.collect(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenThrow(new KubernetesWorkerCollector.Problem(404,"missing"));
            org.mockito.Mockito.when(collector.confirmAbsent(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(observation(stopping,RunLifecycle.WorkerState.TERMINATED,true));
            var cancelled=workerObserver.observe(run.id(),collector);assertThat(cancelled.status()).isEqualTo(RunLifecycle.Status.CANCELLED);
            assertThat(cancelled.operations()).allMatch(op->op.status().equals("CONFIRMED"));
        } finally {closeObservationFixture(run.id());}
        var uncertain=observationFixture();var unknownCollector=org.mockito.Mockito.mock(KubernetesWorkerCollector.class);
        try {
            runs.stop(uncertain.id(),"admin");
            org.mockito.Mockito.when(unknownCollector.collect(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull())).thenThrow(new KubernetesWorkerCollector.Problem(404,"missing"));
            assertThat(workerObserver.observe(uncertain.id(),unknownCollector).status()).isEqualTo(RunLifecycle.Status.STOPPING);
            org.mockito.Mockito.verify(unknownCollector,org.mockito.Mockito.never()).confirmAbsent(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        } finally {closeObservationFixture(uncertain.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void stopDispatchCommitsClaimBeforeCallAndConcurrentRetryDoesNotDeleteAgain() throws Exception {
        var run=observationFixture();var stopper=org.mockito.Mockito.mock(KubernetesWorkerStopper.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var known=observations.record(run.id(),observation(run,RunLifecycle.WorkerState.RUNNING,false));runs.stop(known.id(),"admin");
            org.mockito.Mockito.when(stopper.request(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                KubernetesWorkerStopper.Command command=call.getArgument(0);
                assertThat(command.jobUid()).isEqualTo("fixture-worker-uid");
                assertThat(db.queryForObject("SELECT outcome FROM experiment_worker_stop_receipt WHERE operation_id=?",String.class,command.stopOperationId())).isEqualTo("REQUESTED");
                entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return KubernetesWorkerStopper.Acknowledgement.ACCEPTED;
            });
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var first=pool.submit(()->workerStops.request(run.id(),stopper));
                try {
                    assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                    var duplicate=workerStops.request(run.id(),stopper);assertThat(duplicate.receipt().outcome()).isEqualTo("REQUESTED");
                } finally {release.countDown();}
                var ack=first.get(5,TimeUnit.SECONDS);assertThat(ack.receipt().outcome()).isEqualTo("ACCEPTED");assertThat(ack.run().status()).isEqualTo(RunLifecycle.Status.STOPPING);
                assertThat(ack.run().operations().stream().filter(op->op.kind().equals("STOP")).findFirst().orElseThrow().status()).isEqualTo("PENDING");
                assertThat(workerStops.request(run.id(),stopper).receipt()).isEqualTo(ack.receipt());
            }
            org.mockito.Mockito.verify(stopper,org.mockito.Mockito.times(1)).request(org.mockito.ArgumentMatchers.any());
        } finally {release.countDown();closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void stopDispatchRefusesUnknownUidAndRecordsConflictWithoutNameOnlyFallback() throws Exception {
        var run=observationFixture();var stopper=org.mockito.Mockito.mock(KubernetesWorkerStopper.class);
        try {
            runs.stop(run.id(),"admin");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->workerStops.request(run.id(),stopper)).hasMessage("Stop dispatch requires a stopping run with a bound worker UID.");
            org.mockito.Mockito.verifyNoInteractions(stopper);
            var stopping=runs.get(run.id());observations.record(run.id(),observation(stopping,RunLifecycle.WorkerState.RUNNING,false));
            org.mockito.Mockito.when(stopper.request(org.mockito.ArgumentMatchers.any())).thenThrow(new KubernetesWorkerStopper.Problem(409,"conflict"));
            var result=workerStops.request(run.id(),stopper);assertThat(result.receipt().outcome()).isEqualTo("UID_CONFLICT");assertThat(result.run().status()).isEqualTo(RunLifecycle.Status.STOPPING);
            assertThat(workerStops.request(run.id(),stopper).receipt()).isEqualTo(result.receipt());
            org.mockito.Mockito.verify(stopper,org.mockito.Mockito.times(1)).request(org.mockito.ArgumentMatchers.any());
        } finally {closeObservationFixture(run.id());}
    }
    @Test void preflightPersistsClusterBindingAndRejectsConnectionGrantDrift() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());var clusters=clusterFixture(run);
        var original=clusters.binding(run.targetId(),executionPolicy().namespace());
        var changed=new ExecutionClusterClients.Binding(original.targetId(),2,original.origin(),original.addresses(),original.namespace(),original.operations(),original.caSha256());
        try(var destinations=new ExecutionDestinationPolicy(Map.of("https://fixture.invalid",List.of("192.0.2.10")),host->List.of(java.net.InetAddress.getByName("192.0.2.10")),java.time.Duration.ofSeconds(2))) {
            var gate=new ExecutionPreflight(runs,snapshots,plans,destinations,new ExecutionRuntimePolicy(executionPolicy()),clusters,sourcePolicy);var review=gate.prepare(run.id());
            org.mockito.Mockito.when(clusters.binding(run.targetId(),executionPolicy().namespace())).thenReturn(changed);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->gate.persist(run.id(),review.plan().sha256(),review.destination(),"admin")).hasMessage("Execution policy, destination or run changed after plan review.");
            org.mockito.Mockito.when(clusters.binding(run.targetId(),executionPolicy().namespace())).thenReturn(original);
            var saved=gate.persist(run.id(),review.plan().sha256(),review.destination(),"admin");
            assertThat(json.readTree(saved.canonicalJson()).path("schemaVersion").asText()).isEqualTo("k6-execution-plan-v2");
            org.mockito.Mockito.when(clusters.binding(run.targetId(),executionPolicy().namespace())).thenReturn(changed);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->gate.verifySaved(run.id(),saved.sha256(),review.destination())).hasMessage("Saved execution plan differs from current dispatch inputs.");
            assertThat(plans.get(run.id()).canonicalJson()).isEqualTo(saved.canonicalJson());
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void boundWorkerControlTraversesHttpsObserveDeleteAndConfirmedAbsence() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());var fixtureData=new KubernetesWorkerCollectorTest();
        try(var fixture=fixtureData.new Fixture()) {
            var store=org.mockito.Mockito.mock(dev.loadpilot.integrations.ConnectionStore.class);
            org.mockito.Mockito.when(store.view(run.targetId())).thenReturn(new dev.loadpilot.integrations.ConnectionStore.View(1,fixture.origin(),fixture.ca,true,"","",false,null));
            org.mockito.Mockito.when(store.kubernetesMaterial(run.targetId())).thenReturn(new dev.loadpilot.integrations.ConnectionStore.KubernetesMaterial(1,fixture.origin(),fixture.ca,"fixture-token"));
            String config=json.writeValueAsString(List.of(Map.of("targetId",run.targetId().toString(),"connectionRevision",1,"origin",fixture.origin(),"addresses",List.of("127.0.0.1"),"namespace",executionPolicy().namespace(),"operations",List.of("READ","STOP"))));
            var clients=new ExecutionClusterClients(config,json,store);var binding=clients.binding(run.targetId(),executionPolicy().namespace());
            var preview=plans.preview(run.id(),executionPolicy(),executionTarget(),binding);plans.persist(run.id(),preview.sha256(),executionPolicy(),executionTarget(),binding,"admin");
            fixture.overrideJob=plans.workerJob(run);fixture.overrideJob.getMetadata().uid("fixture-job").resourceVersion("123");
            fixture.overrideJob.status(new io.kubernetes.client.openapi.models.V1JobStatus().active(1));fixture.allowDelete=true;
            fixture.overrideJob.getSpec().suspend(false);
            fixture.overridePods=List.of(new io.kubernetes.client.openapi.models.V1Pod().metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().name(run.workerName()+"-pod").namespace(executionPolicy().namespace()).uid("running-pod").labels(fixture.overrideJob.getMetadata().getLabels())
                    .ownerReferences(List.of(new io.kubernetes.client.openapi.models.V1OwnerReference().apiVersion("batch/v1").kind("Job").name(run.workerName()).uid("fixture-job").controller(true))))
                    .spec(new io.kubernetes.client.openapi.models.V1PodSpec().containers(List.of(new io.kubernetes.client.openapi.models.V1Container().name("k6").image("fixture.invalid/k6"))))
                    .status(new io.kubernetes.client.openapi.models.V1PodStatus().phase("Running").containerStatuses(List.of(new io.kubernetes.client.openapi.models.V1ContainerStatus().name("k6").image("fixture.invalid/k6").imageID("fixture").ready(true).restartCount(0)
                            .state(new io.kubernetes.client.openapi.models.V1ContainerState().running(new io.kubernetes.client.openapi.models.V1ContainerStateRunning()))))));
            var control=new WorkerControlService(runs,plans,clients,workerObserver,workerStops);
            var running=control.observe(run.id());assertThat(running.status()).isEqualTo(RunLifecycle.Status.RUNNING);assertThat(running.workerUid()).isEqualTo("fixture-job");
            runs.stop(run.id(),"admin");var acknowledgement=control.dispatchPendingStop(run.id());
            assertThat(acknowledgement.receipt().outcome()).isEqualTo("ACCEPTED");assertThat(acknowledgement.run().status()).isEqualTo(RunLifecycle.Status.STOPPING);
            var cancelled=control.observe(run.id());assertThat(cancelled.status()).isEqualTo(RunLifecycle.Status.CANCELLED);assertThat(cancelled.verdict()).isEqualTo("UNEVALUATED");
            assertThat(fixture.requests.stream().filter(request->request.startsWith("DELETE ")).count()).isEqualTo(1);
        } finally {closeObservationFixture(run.id());}
    }
    record PreparationFixture(RunIntents.Run run,ExecutionPlans.Saved plan,ExecutionDestinationPolicy destinations,
                              dev.loadpilot.experiments.WorkerPreparationDispatch coordinator) implements AutoCloseable {
        @Override public void close(){destinations.close();}
    }
    PreparationFixture preparationFixture() throws Exception {
        return preparationFixture(null,null);
    }
    PreparationFixture preparationFixture(String origin,String ca) throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var destinations=new ExecutionDestinationPolicy(Map.of("https://fixture.invalid",List.of("192.0.2.10")),host->List.of(java.net.InetAddress.getByName("192.0.2.10")),java.time.Duration.ofSeconds(2));
        try {
            var clusters=clusterFixture(run);
            if(origin!=null) org.mockito.Mockito.when(clusters.binding(run.targetId(),executionPolicy().namespace())).thenReturn(new ExecutionClusterClients.Binding(run.targetId(),1,origin,List.of("127.0.0.1"),executionPolicy().namespace(),List.of("READ","STOP"),dev.loadpilot.integrations.k6.K6PlanEnvelope.hash(ca)));
            var gate=new ExecutionPreflight(runs,snapshots,plans,destinations,new ExecutionRuntimePolicy(executionPolicy()),clusters,sourcePolicy);
            var preview=gate.prepare(run.id());var saved=gate.persist(run.id(),preview.plan().sha256(),preview.destination(),"admin");
            return new PreparationFixture(run,saved,destinations,new dev.loadpilot.experiments.WorkerPreparationDispatch(preparationReceipts,runs,plans,gate,json));
        } catch(Exception failure){destinations.close();closeObservationFixture(run.id());throw failure;}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void preparationCommitsIntentBeforeIoAndSuppressesConcurrentDuplicate() throws Exception {
        try(var fixture=preparationFixture()) {
            var run=fixture.run();var preparer=org.mockito.Mockito.mock(dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.class);
            var files=dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.FILES;var job=dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.JOB;
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(()->fixture.coordinator().request(run.id(),fixture.plan().sha256(),job,preparer)).hasMessage("A preceding worker resource still requires acknowledgement or reconciliation.");
                org.mockito.Mockito.verifyNoInteractions(preparer);
                org.mockito.Mockito.when(preparer.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(fixture.plan().submitOperationId()),org.mockito.ArgumentMatchers.eq(files))).thenAnswer(call->{
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    dev.loadpilot.integrations.k6.K6PlanEnvelope.Prepared sent=call.getArgument(0);assertThat(sent.canonicalJson()).isEqualTo(fixture.plan().canonicalJson());
                    assertThat(db.queryForObject("SELECT outcome FROM experiment_worker_preparation WHERE plan_id=? AND resource='FILES'",String.class,fixture.plan().id())).isEqualTo("REQUESTED");
                    entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();
                    return new dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Receipt(files,executionPolicy().namespace(),run.workerName(),"files-uid");
                });
                try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                    var first=pool.submit(()->fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer));
                    try {assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer).receipt().outcome()).isEqualTo("REQUESTED");}
                    finally {release.countDown();}
                    var ack=first.get(5,TimeUnit.SECONDS);assertThat(ack.receipt().outcome()).isEqualTo("ACKNOWLEDGED");
                    assertThat(fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer).receipt()).isEqualTo(ack.receipt());
                    assertThat(ack.run().status()).isEqualTo(RunLifecycle.Status.SUBMITTING);assertThat(ack.run().operations().getFirst().status()).isEqualTo("PENDING");
                    assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE action='WORKER_PREPARATION_INTENT' AND object_id=?",Integer.class,ack.receipt().id())).isEqualTo(1);
                }
                org.mockito.Mockito.verify(preparer,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            } finally {release.countDown();closeObservationFixture(run.id());}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void httpsPreparationPersistsThreeReceiptsWithoutActivatingTheJob() throws Exception {
        var protocol=new KubernetesWorkerPreparerTest();
        try(var server=protocol.new Fixture();var fixture=preparationFixture(server.origin(),server.ca)) {
            var run=fixture.run();
            try(var preparer=new dev.loadpilot.integrations.k6.KubernetesWorkerPreparer(server.client(),json)) {
                for(var resource:dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.values()) {
                    var result=fixture.coordinator().request(run.id(),fixture.plan().sha256(),resource,preparer);
                    assertThat(result.receipt().outcome()).isEqualTo("ACKNOWLEDGED");assertThat(result.receipt().planHash()).isEqualTo(fixture.plan().sha256());
                    assertThat(result.run().status()).isEqualTo(RunLifecycle.Status.SUBMITTING);assertThat(result.run().workerUid()).isNull();
                }
                assertThat(server.requests).hasSize(3).allMatch(request->request.startsWith("POST "));
                assertThat(json.readTree(server.bodies.get(2)).path("spec").path("suspend").asBoolean()).isTrue();
                var previous=fixture.coordinator().request(run.id(),fixture.plan().sha256(),dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.JOB,preparer);
                assertThat(previous.receipt().outcome()).isEqualTo("ACKNOWLEDGED");assertThat(server.requests).hasSize(3);
            } finally {closeObservationFixture(run.id());}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void stopDuringPreparationRetainsTheReceiptAndBlocksTheNextCreation() throws Exception {
        try(var fixture=preparationFixture()) {
            var run=fixture.run();var preparer=org.mockito.Mockito.mock(dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.class);var files=dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.FILES;
            try {
                org.mockito.Mockito.when(preparer.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
                    runs.stop(run.id(),"admin");return new dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Receipt(files,executionPolicy().namespace(),run.workerName(),"files-uid");
                });
                var result=fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer);assertThat(result.run().status()).isEqualTo(RunLifecycle.Status.STOPPING);assertThat(result.receipt().outcome()).isEqualTo("ACKNOWLEDGED");
                assertThat(fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer).receipt()).isEqualTo(result.receipt());
                org.assertj.core.api.Assertions.assertThatThrownBy(()->fixture.coordinator().request(run.id(),fixture.plan().sha256(),dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.NETWORK,preparer)).hasMessage("This run cannot prepare or dispatch a worker plan.");
                org.mockito.Mockito.verify(preparer,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
                assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_preparation WHERE plan_id=?",Integer.class,fixture.plan().id())).isEqualTo(1);
            } finally {closeObservationFixture(run.id());}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void uncertainAndCrashLeftPreparationIntentsNeverRetryOrAdvance() throws Exception {
        for(boolean crash:List.of(false,true)) try(var fixture=preparationFixture()) {
            var run=fixture.run();var preparer=org.mockito.Mockito.mock(dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.class);var files=dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.FILES;
            try {
                if(crash) preparationReceipts.claim(run.id(),fixture.plan().id(),fixture.plan().sha256(),files); // Committed intent, deliberately no callback.
                else org.mockito.Mockito.when(preparer.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenThrow(new dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Problem(503,"private upstream detail"));
                var first=fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer);assertThat(first.receipt().outcome()).isEqualTo(crash?"REQUESTED":"UNCERTAIN");
                assertThat(fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer).receipt()).isEqualTo(first.receipt());
                org.assertj.core.api.Assertions.assertThatThrownBy(()->fixture.coordinator().request(run.id(),fixture.plan().sha256(),dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.NETWORK,preparer)).hasMessage("A preceding worker resource still requires acknowledgement or reconciliation.");
                org.mockito.Mockito.verify(preparer,org.mockito.Mockito.times(crash?0:1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
                assertThat(runs.get(run.id()).status()).isEqualTo(RunLifecycle.Status.SUBMITTING);
            } finally {closeObservationFixture(run.id());}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void preparationRefusesWrongHashChangedTargetAndAmbientTransactionBeforeIo() throws Exception {
        try(var fixture=preparationFixture()) {
            var run=fixture.run();var preparer=org.mockito.Mockito.mock(dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.class);var files=dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.FILES;
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(()->fixture.coordinator().request(run.id(),"0".repeat(64),files,preparer)).hasMessage("Preparation requires the exact saved plan hash.");
                org.assertj.core.api.Assertions.assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer))).hasMessage("Worker preparation must begin outside an existing transaction.");
                db.update("UPDATE registered_target SET revision=revision+1 WHERE id=?",run.targetId());
                org.assertj.core.api.Assertions.assertThatThrownBy(()->fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer)).hasMessage("Target changed after review. Review a new snapshot.");
                org.mockito.Mockito.verifyNoInteractions(preparer);assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_preparation WHERE plan_id=?",Integer.class,fixture.plan().id())).isZero();
            } finally {closeObservationFixture(run.id());}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void preparationReceiptCannotChangeItsEvidenceOrResourceIdentity() throws Exception {
        try(var fixture=preparationFixture()) {
            var run=fixture.run();var preparer=org.mockito.Mockito.mock(dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.class);var files=dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.FILES;
            try {
                org.mockito.Mockito.when(preparer.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(new dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Receipt(files,executionPolicy().namespace(),run.workerName(),"files-uid"));
                var ack=fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer).receipt();
                assertThat(preparationReceipts.finish(ack.id(),"ACKNOWLEDGED","files-uid")).isEqualTo(ack);
                org.assertj.core.api.Assertions.assertThatThrownBy(()->preparationReceipts.finish(ack.id(),"ACKNOWLEDGED","different-uid")).hasMessage("Preparation intent already has different evidence.");
                db.update("UPDATE experiment_worker_preparation SET namespace='other' WHERE id=?",ack.id());
                org.assertj.core.api.Assertions.assertThatThrownBy(()->fixture.coordinator().request(run.id(),fixture.plan().sha256(),files,preparer)).hasMessage("Preparation receipt identity cannot be verified.");
                org.assertj.core.api.Assertions.assertThatThrownBy(()->fixture.coordinator().request(run.id(),fixture.plan().sha256(),dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.NETWORK,preparer)).hasMessage("Preparation receipt identity cannot be verified.");
                org.mockito.Mockito.verify(preparer,org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            } finally {closeObservationFixture(run.id());}
        }
    }
    void boundPreparationFacade(boolean allowed) throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());var protocol=new KubernetesWorkerPreparerTest();
        try(var server=protocol.new Fixture();var destinations=new ExecutionDestinationPolicy(Map.of("https://fixture.invalid",List.of("192.0.2.10")),host->List.of(java.net.InetAddress.getByName("192.0.2.10")),java.time.Duration.ofSeconds(2))) {
            var store=org.mockito.Mockito.mock(dev.loadpilot.integrations.ConnectionStore.class);
            org.mockito.Mockito.when(store.view(run.targetId())).thenReturn(new dev.loadpilot.integrations.ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));
            org.mockito.Mockito.when(store.kubernetesMaterial(run.targetId())).thenAnswer(call->{
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(db.queryForObject("SELECT count(*) FROM experiment_worker_preparation p JOIN experiment_execution_plan e ON e.id=p.plan_id WHERE e.run_id=? AND p.outcome='REQUESTED'",Integer.class,run.id())).isEqualTo(1);
                return new dev.loadpilot.integrations.ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token");
            });
            String config=json.writeValueAsString(List.of(Map.of("targetId",run.targetId().toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace",executionPolicy().namespace(),"operations",allowed?List.of("READ","STOP","PREPARE"):List.of("READ","STOP"))));
            var clients=new ExecutionClusterClients(config,json,store);
            var gate=new ExecutionPreflight(runs,snapshots,plans,destinations,new ExecutionRuntimePolicy(executionPolicy()),clients,sourcePolicy);
            var reviewed=gate.prepare(run.id());var saved=gate.persist(run.id(),reviewed.plan().sha256(),reviewed.destination(),"admin");
            var dispatch=new dev.loadpilot.experiments.WorkerPreparationDispatch(preparationReceipts,runs,plans,gate,json);
            var facade=new dev.loadpilot.experiments.WorkerPreparationService(runs,plans,clients,dispatch,json);
            var resources=allowed?dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.values():new dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource[]{dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.FILES};
            for(var resource:resources) {
                var result=facade.prepare(run.id(),saved.sha256(),resource);assertThat(result.receipt().outcome()).isEqualTo(allowed?"ACKNOWLEDGED":"DENIED");assertThat(result.run().status()).isEqualTo(RunLifecycle.Status.SUBMITTING);
            }
            if(allowed) {
                assertThat(server.requests).hasSize(3);assertThat(server.authorization).containsExactly("Bearer fixture-token","Bearer fixture-token","Bearer fixture-token");
                assertThat(json.readTree(server.bodies.get(2)).path("spec").path("suspend").asBoolean()).isTrue();
            } else {assertThat(server.requests).isEmpty();org.mockito.Mockito.verify(store,org.mockito.Mockito.never()).kubernetesMaterial(run.targetId());}
            runs.stop(run.id(),"admin");org.mockito.Mockito.clearInvocations(store);
            assertThat(facade.prepare(run.id(),saved.sha256(),dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.FILES).receipt().outcome()).isEqualTo(allowed?"ACKNOWLEDGED":"DENIED");
            org.mockito.Mockito.verifyNoInteractions(store);assertThat(server.requests).hasSize(allowed?3:0);
        } finally {closeObservationFixture(run.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void savedBindingPreparationFacadeCreatesResourcesAfterIntentAndDoesNotActivate() throws Exception {boundPreparationFacade(true);}
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void savedBindingPreparationFacadeNeedsExplicitGrantAndKeepsDeniedReceipt() throws Exception {boundPreparationFacade(false);}
    @Test void pendingWorkerDoesNotStartLivePollingAndStopStillRequiresTermination() throws Exception {
        var run=observationFixture();
        var pending=observations.record(run.id(),observation(run,RunLifecycle.WorkerState.PENDING,false));
        assertThat(pending.status()).isEqualTo(RunLifecycle.Status.SUBMITTING);assertThat(pending.workerState()).isEqualTo("PENDING");assertThat(pending.workerUid()).isEqualTo("fixture-worker-uid");
        assertThat(pending.operations().getFirst().status()).isEqualTo("CONFIRMED");
        var stopping=runs.stop(run.id(),"admin");var stillPending=observations.record(run.id(),observation(stopping,RunLifecycle.WorkerState.PENDING,false));
        assertThat(stillPending.status()).isEqualTo(RunLifecycle.Status.STOPPING);
        var ended=observations.record(run.id(),observation(stillPending,RunLifecycle.WorkerState.TERMINATED,true));
        assertThat(ended.status()).isEqualTo(RunLifecycle.Status.CANCELLED);assertThat(ended.verdict()).isEqualTo("UNEVALUATED");
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void liveHttpMetricsEndpointUsesBoundWorkerSamplesAndKeepsOutageSeparateFromVerdict() throws Exception {
        var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());var protocol=new KubernetesWorkerLogsTest();
        try(var server=protocol.new Fixture()) {
            var store=org.mockito.Mockito.mock(dev.loadpilot.integrations.ConnectionStore.class);
            org.mockito.Mockito.when(store.view(run.targetId())).thenReturn(new dev.loadpilot.integrations.ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));
            org.mockito.Mockito.when(store.kubernetesMaterial(run.targetId())).thenReturn(new dev.loadpilot.integrations.ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token"));
            String config=json.writeValueAsString(List.of(Map.of("targetId",run.targetId().toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace",executionPolicy().namespace(),"operations",List.of("READ","STOP","METRICS"))));
            var provider=new ExecutionClusterClients(config,json,store);var binding=provider.binding(run.targetId(),executionPolicy().namespace());
            var preview=plans.preview(run.id(),executionPolicy(),executionTarget(),binding);plans.persist(run.id(),preview.sha256(),executionPolicy(),executionTarget(),binding,"admin");
            server.job=io.kubernetes.client.openapi.JSON.deserialize(io.kubernetes.client.openapi.JSON.serialize(plans.workerJob(run)),io.kubernetes.client.openapi.models.V1Job.class);
            server.job.getMetadata().uid("metric-job").resourceVersion("1");server.job.getSpec().suspend(false);server.job.status(new io.kubernetes.client.openapi.models.V1JobStatus().active(1));server.selected=List.of(protocol.pod(server.job));
            var control=new WorkerControlService(runs,plans,provider,workerObserver,workerStops);var running=control.observe(run.id());assertThat(running.status()).isEqualTo(RunLifecycle.Status.RUNNING);
            var inspector=org.mockito.Mockito.mock(dev.loadpilot.integrations.SystemInspector.class);var check=new dev.loadpilot.integrations.Inspection.Check("READY","fixture",java.time.Instant.now(),"Kubernetes PodMetrics",200);
            org.mockito.Mockito.when(inspector.resources(run.targetId())).thenAnswer(call->new dev.loadpilot.integrations.Inspection(run.targetId(),snapshots.get(run.snapshotId()).inputs().target().revision(),1,java.time.Instant.now(),null,check,check,null,null,
                    List.of(new dev.loadpilot.integrations.Inspection.Pod("target-api","Running",true,0,null,null,null,null,new dev.loadpilot.integrations.Inspection.Usage("READY",java.time.Instant.now(),"30s",java.math.BigDecimal.valueOf(50),java.math.BigDecimal.valueOf(67108864))))));
            var http=new dev.loadpilot.experiments.WorkerHttpMetrics(runs,plans,provider,json);var metrics=new dev.loadpilot.experiments.RunMetrics(runs,snapshots,inspector,http);
            var endpoint=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new dev.loadpilot.experiments.RunApi(runs,metrics,finalResults,starts,startWorkflows)).build();
            String url="/api/experiments/runs/"+run.id()+"/metrics";
            for(int repeat=0;repeat<2;repeat++) endpoint.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.http.state").value("READY"))
                    .andExpect(jsonPath("$.http.requestCount").value(2)).andExpect(jsonPath("$.http.requestsPerSecond").value(.2)).andExpect(jsonPath("$.http.p95LatencyMs").value(290.0))
                    .andExpect(jsonPath("$.http.errorRatio").value(.5)).andExpect(jsonPath("$.resources.cpuMillicores").value(50)).andExpect(jsonPath("$.verdict").value("UNEVALUATED"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-token"))));
            server.denyLogs=true;
            endpoint.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING")).andExpect(jsonPath("$.http.state").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.http.requestsPerSecond").isEmpty()).andExpect(jsonPath("$.resources.state").value("READY")).andExpect(jsonPath("$.verdict").value("UNEVALUATED"));
            assertThat(runs.get(run.id()).revision()).isEqualTo(running.revision());assertThat(server.requests).allMatch(request->request.startsWith("GET "));
        } finally {closeObservationFixture(run.id());}
    }
    record ActivationFixture(RunIntents.Run run,ExecutionPlans.Saved plan,KubernetesWorkerActivatorTest.Fixture server,
                             ExecutionDestinationPolicy destinations,ExecutionClusterClients clients,dev.loadpilot.integrations.ConnectionStore store,
                             dev.loadpilot.experiments.WorkerActivationDispatch dispatch,dev.loadpilot.experiments.WorkerActivationService facade) implements AutoCloseable {
        @Override public void close(){server.close();destinations.close();}
    }
    ActivationFixture activationFixture(boolean ready) throws Exception {
        var protocol=new KubernetesWorkerActivatorTest();var server=protocol.new Fixture();var run=runs.claimSubmission(runs.reserve(runRequest(),"admin").id());
        var destinations=new ExecutionDestinationPolicy(Map.of("https://fixture.invalid",List.of("192.0.2.10")),host->List.of(java.net.InetAddress.getByName("192.0.2.10")),java.time.Duration.ofSeconds(2));
        try {
            var store=org.mockito.Mockito.mock(dev.loadpilot.integrations.ConnectionStore.class);
            org.mockito.Mockito.when(store.view(run.targetId())).thenReturn(new dev.loadpilot.integrations.ConnectionStore.View(1,server.origin(),server.ca,true,"","",false,null));
            org.mockito.Mockito.when(store.kubernetesMaterial(run.targetId())).thenAnswer(call->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new dev.loadpilot.integrations.ConnectionStore.KubernetesMaterial(1,server.origin(),server.ca,"fixture-token");});
            String config=json.writeValueAsString(List.of(Map.of("targetId",run.targetId().toString(),"connectionRevision",1,"origin",server.origin(),"addresses",List.of("127.0.0.1"),"namespace",executionPolicy().namespace(),"operations",List.of("READ","STOP","PREPARE","ACTIVATE"))));
            var clients=new ExecutionClusterClients(config,json,store);var gate=new ExecutionPreflight(runs,snapshots,plans,destinations,new ExecutionRuntimePolicy(executionPolicy()),clients,sourcePolicy);
            var preview=gate.prepare(run.id());var saved=gate.persist(run.id(),preview.plan().sha256(),preview.destination(),"admin");
            server.configure(new dev.loadpilot.integrations.k6.K6PlanEnvelope.Prepared(saved.canonicalJson(),saved.sha256()));
            if(ready) {
                var preparer=org.mockito.Mockito.mock(dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.class);
                org.mockito.Mockito.when(preparer.create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
                    dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource resource=call.getArgument(2);String uid=switch(resource) {case FILES->"files-uid";case NETWORK->"network-uid";case JOB->"job-uid";};
                    return new dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Receipt(resource,executionPolicy().namespace(),run.workerName(),uid);
                });
                var preparation=new dev.loadpilot.experiments.WorkerPreparationDispatch(preparationReceipts,runs,plans,gate,json);
                for(var resource:dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource.values()) preparation.request(run.id(),saved.sha256(),resource,preparer);
            }
            var dispatch=new dev.loadpilot.experiments.WorkerActivationDispatch(runs,plans,gate,activationReceipts,json);
            return new ActivationFixture(run,saved,server,destinations,clients,store,dispatch,new dev.loadpilot.experiments.WorkerActivationService(runs,plans,clients,dispatch,json));
        } catch(Exception failure){server.close();destinations.close();closeObservationFixture(run.id());throw failure;}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void scopedActivationKeepsPendingUntilRunningThenSupportsUidStopAndAbsence() throws Exception {
        try(var f=activationFixture(true)) {
            var id=f.run().id();
            try {
                var control=new WorkerControlService(runs,plans,f.clients(),workerObserver,workerStops);
                var pending=control.observe(id);assertThat(pending.status()).isEqualTo(RunLifecycle.Status.SUBMITTING);assertThat(pending.workerState()).isEqualTo("PENDING");
                var ack=f.facade().activate(id,f.plan().sha256());assertThat(ack.receipt().outcome()).isEqualTo("ACKNOWLEDGED");assertThat(ack.run().status()).isEqualTo(RunLifecycle.Status.SUBMITTING);assertThat(ack.run().workerState()).isEqualTo("PENDING");
                assertThat(ack.run().operations().stream().filter(op->op.kind().equals("ACTIVATE")).findFirst().orElseThrow().status()).isEqualTo("CONFIRMED");
                int count=f.server().requests.size();org.mockito.Mockito.clearInvocations(f.store());assertThat(f.facade().activate(id,f.plan().sha256()).receipt()).isEqualTo(ack.receipt());org.mockito.Mockito.verifyNoInteractions(f.store());assertThat(f.server().requests).hasSize(count);
                assertThat(control.observe(id).status()).isEqualTo(RunLifecycle.Status.RUNNING);runs.stop(id,"admin");
                assertThat(control.dispatchPendingStop(id).receipt().outcome()).isEqualTo("ACCEPTED");assertThat(runs.get(id).status()).isEqualTo(RunLifecycle.Status.STOPPING);
                var cancelled=control.observe(id);assertThat(cancelled.status()).isEqualTo(RunLifecycle.Status.CANCELLED);assertThat(cancelled.verdict()).isEqualTo("UNEVALUATED");
                assertThat(f.server().requests.stream().filter(value->value.startsWith("PATCH ")).count()).isEqualTo(1);assertThat(f.server().requests.stream().filter(value->value.startsWith("DELETE ")).count()).isEqualTo(1);
            } finally {closeObservationFixture(id);}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void activationCommitsExactPatchAndBoundUidBeforeCallAndConcurrentRetryDoesNotRepeat() throws Exception {
        try(var f=activationFixture(true);var activator=new dev.loadpilot.integrations.k6.KubernetesWorkerActivator(f.server().client(),json)) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();var id=f.run().id();
            dev.loadpilot.experiments.WorkerActivationDispatch.Writer writer=(command,operation)->{
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(db.queryForObject("SELECT outcome FROM experiment_worker_activation WHERE operation_id=?",String.class,operation)).isEqualTo("REQUESTED");assertThat(runs.get(id).workerUid()).isEqualTo("job-uid");
                assertThat(db.queryForObject("SELECT patch_json FROM experiment_worker_activation WHERE operation_id=?",String.class,operation)).isEqualTo(command.patchJson());calls.incrementAndGet();entered.countDown();
                try {assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException();}
                return new dev.loadpilot.integrations.k6.KubernetesWorkerActivator.Receipt(command.jobUid(),"11");
            };
            try {
                try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                    var first=pool.submit(()->f.dispatch().activate(id,f.plan().sha256(),activator::prepare,writer));
                    try {assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(f.dispatch().activate(id,f.plan().sha256(),activator::prepare,writer).receipt().outcome()).isEqualTo("REQUESTED");}
                    finally {release.countDown();}
                    var ack=first.get(5,TimeUnit.SECONDS);assertThat(ack.receipt().outcome()).isEqualTo("ACKNOWLEDGED");assertThat(calls.get()).isEqualTo(1);assertThat(activationReceipts.finish(ack.receipt().operationId(),"ACKNOWLEDGED","11")).isEqualTo(ack.receipt());
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->activationReceipts.finish(ack.receipt().operationId(),"ACKNOWLEDGED","12")).hasMessage("Activation intent already has different evidence.");
                }
            } finally {release.countDown();closeObservationFixture(id);}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void activationRefusesMissingAcknowledgementsAndStopBeforeClaim() throws Exception {
        try(var f=activationFixture(false);var activator=new dev.loadpilot.integrations.k6.KubernetesWorkerActivator(f.server().client(),json)) {
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(()->f.dispatch().activate(f.run().id(),f.plan().sha256(),activator::prepare,(command,op)->{throw new AssertionError("No activation expected");})).hasMessage("Every prepared resource requires matching acknowledged evidence before activation.");assertThat(f.server().requests).isEmpty();
            } finally {closeObservationFixture(f.run().id());}
        }
        try(var f=activationFixture(true);var activator=new dev.loadpilot.integrations.k6.KubernetesWorkerActivator(f.server().client(),json)) {
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(()->f.dispatch().activate(f.run().id(),f.plan().sha256(),(exact,resources)->{var prepared=activator.prepare(exact,resources);runs.stop(f.run().id(),"admin");return prepared;},(command,op)->{throw new AssertionError("No activation expected");})).hasMessage("This run cannot activate a prepared worker.");
                assertThat(f.server().requests).noneMatch(value->value.startsWith("PATCH "));assertThat(activationReceipts.get(f.plan().id())).isNull();
            } finally {closeObservationFixture(f.run().id());}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void inFlightActivationCannotUndoStopAndUncertainReceiptDoesNotRetry() throws Exception {
        for(boolean stop:List.of(true,false)) try(var f=activationFixture(true);var activator=new dev.loadpilot.integrations.k6.KubernetesWorkerActivator(f.server().client(),json)) {
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            try {
                dev.loadpilot.experiments.WorkerActivationDispatch.Writer writer=(command,operation)->{
                    calls.incrementAndGet();if(stop) {runs.stop(f.run().id(),"admin");return new dev.loadpilot.integrations.k6.KubernetesWorkerActivator.Receipt(command.jobUid(),"11");}
                    throw new dev.loadpilot.integrations.k6.KubernetesWorkerActivator.Problem(503,"private upstream detail");
                };
                var first=f.dispatch().activate(f.run().id(),f.plan().sha256(),activator::prepare,writer);assertThat(first.receipt().outcome()).isEqualTo(stop?"ACKNOWLEDGED":"UNCERTAIN");assertThat(first.run().status()).isEqualTo(stop?RunLifecycle.Status.STOPPING:RunLifecycle.Status.SUBMITTING);
                assertThat(f.dispatch().activate(f.run().id(),f.plan().sha256(),activator::prepare,writer).receipt()).isEqualTo(first.receipt());assertThat(calls.get()).isEqualTo(1);
                if(stop) assertThat(runs.get(f.run().id()).operations().stream().filter(op->op.kind().equals("STOP")).findFirst().orElseThrow().status()).isEqualTo("PENDING");
            } finally {closeObservationFixture(f.run().id());}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void crashLeftActivationIntentIsHistoricalAndNeverResent() throws Exception {
        try(var f=activationFixture(true);var activator=new dev.loadpilot.integrations.k6.KubernetesWorkerActivator(f.server().client(),json)) {
            try {
                var inputs=activationReceipts.inputs(f.run().id(),f.plan().sha256());var command=activator.prepare(new dev.loadpilot.integrations.k6.K6PlanEnvelope.Prepared(f.plan().canonicalJson(),f.plan().sha256()),inputs.resources());
                var claim=activationReceipts.claim(inputs,command);assertThat(claim.dispatch()).isTrue();int reads=f.server().requests.size();
                org.mockito.Mockito.clearInvocations(f.store());assertThat(f.facade().activate(f.run().id(),f.plan().sha256()).receipt().outcome()).isEqualTo("REQUESTED");org.mockito.Mockito.verifyNoInteractions(f.store());assertThat(f.server().requests).hasSize(reads).noneMatch(value->value.startsWith("PATCH "));
                assertThat(runs.get(f.run().id()).status()).isEqualTo(RunLifecycle.Status.SUBMITTING);assertThat(runs.get(f.run().id()).workerUid()).isEqualTo("job-uid");
            } finally {closeObservationFixture(f.run().id());}
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void legacyWorkerControlRefusesAccessWithoutRewritingTheSavedPlan() throws Exception {
        var run=observationFixture();var clients=org.mockito.Mockito.mock(ExecutionClusterClients.class);var original=plans.get(run.id());
        try {
            var control=new WorkerControlService(runs,plans,clients,workerObserver,workerStops);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->control.observe(run.id())).hasMessage("This legacy plan has no frozen cluster binding. Review new inputs before live access.");
            org.mockito.Mockito.verifyNoInteractions(clients);assertThat(plans.get(run.id())).isEqualTo(original);
        } finally {closeObservationFixture(run.id());}
    }
}
