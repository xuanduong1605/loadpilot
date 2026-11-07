package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.k6.K6JobPlan;
import dev.loadpilot.integrations.k6.K6PlanEnvelope;
import dev.loadpilot.integrations.k6.ExecutionClusterClients;
import dev.loadpilot.scenarios.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class K6JobPlanTest {
    final ObjectMapper json=new ObjectMapper();
    final K6JobPlan planner=new K6JobPlan(json,Validation.buildDefaultValidatorFactory().getValidator());
    final Instant now=Instant.parse("2026-11-06T00:00:00Z");
    final K6JobPlan.Policy policy=new K6JobPlan.Policy("loadpilot-runners","example.invalid/k6@sha256:"+"a".repeat(64),"loadpilot-runner","sandboxed",32);
    final K6JobPlan.PinnedTarget destination=new K6JobPlan.PinnedTarget("fixture.invalid","192.0.2.10",443);
    record Fixture(ExperimentSnapshots.Snapshot snapshot,RunDispatch.Ticket ticket) {}
    Fixture fixture(LoadProfile profile,String source) throws Exception {
        var settings=new ExperimentSettingsApi.Settings(1,5,20,5,300,0.01,1,2,60);
        var bounds=new ExecutionBounds(null,1,2,1,250,128,1000,3,2,1);
        var slo=new SloPolicy(SloPolicy.Percentile.P99,300,0.01,SloPolicy.SuccessDefinition.HTTP_200_399_WITHOUT_TRANSPORT_ERROR,null,null,null,List.of());
        var time=now.atOffset(ZoneOffset.UTC);UUID snapshot=UUID.randomUUID(),attempt=UUID.randomUUID();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        var script=new ScenarioApi.Version(UUID.randomUUID(),"fixture",1,source,hash,"admin",time,true);
        var approved=new ScenarioStore.ApprovedVersion(script,UUID.randomUUID(),UUID.randomUUID(),"static-review-v1","8.19.0","admin",time);
        var input=new ExperimentSnapshots.Inputs("experiment-input-v5",UUID.randomUUID(),1,"SINGLE_RUN",
                new ExperimentSnapshots.TargetInput(UUID.randomUUID(),"fixture","https://fixture.invalid","STAGING","fixture","api","api",1),
                approved,settings,"iterations/s",profile.executor(),profile.measurementSeconds(),"<=",bounds,25L,1,profile,profile.measurementRate(settings),slo);
        return new Fixture(new ExperimentSnapshots.Snapshot(snapshot,input,"b".repeat(64),"admin",time,true,false),
                new RunDispatch.Ticket(UUID.randomUUID(),snapshot,2,attempt,UUID.randomUUID(),UUID.randomUUID(),"lp-"+attempt,25,time.plusSeconds(60),time));
    }
    LoadProfile steady() { return new LoadProfile(LoadProfile.Mode.SINGLE_RUN,LoadProfile.Shape.STEADY,3,List.of(new LoadProfile.Stage(20,3)),15); }
    @Test void constructsBoundedNonprivilegedSingleWorkerWithExactSourceAndNoSubmissionCapability() throws Exception {
        String source="// Xin chào\nexport default function() {}\n";var f=fixture(steady(),source);
        var plan=planner.construct(f.snapshot(),f.ticket(),policy,destination,now);
        assertThat(plan.submissionReady()).isFalse();
        assertThat(plan.files().getImmutable()).isTrue();
        assertThat(plan.files().getData().get("scenario.js")).isEqualTo(source);
        assertThat(plan.job().getSpec().getSuspend()).isTrue();
        assertThat(plan.job().getSpec().getBackoffLimit()).isZero();
        assertThat(plan.job().getSpec().getActiveDeadlineSeconds()).isEqualTo(25);
        assertThat(plan.job().getSpec().getParallelism()).isEqualTo(1);
        var pod=plan.job().getSpec().getTemplate().getSpec();var worker=pod.getContainers().getFirst();
        assertThat(pod.getRestartPolicy()).isEqualTo("Never");
        assertThat(pod.getActiveDeadlineSeconds()).isEqualTo(23);
        assertThat(pod.getActiveDeadlineSeconds()+pod.getTerminationGracePeriodSeconds()).isEqualTo(25);
        assertThat(pod.getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getHostNetwork()).isFalse();assertThat(pod.getHostPID()).isFalse();assertThat(pod.getHostIPC()).isFalse();
        assertThat(pod.getRuntimeClassName()).isEqualTo("sandboxed");
        assertThat(worker.getCommand()).containsExactly("k6");
        assertThat(worker.getArgs()).contains("--log-output=none","--quiet","--out","json=-");
        assertThat(worker.getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
        assertThat(worker.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
        assertThat(worker.getSecurityContext().getCapabilities().getDrop()).containsExactly("ALL");
        assertThat(worker.getSecurityContext().getRunAsUser()).isEqualTo(65532);
        assertThat(worker.getResources().getLimits().get("cpu").getNumber()).isEqualByComparingTo("0.25");
        assertThat(worker.getResources().getLimits().get("memory").getNumber()).isEqualByComparingTo("134217728");
        var options=json.readTree(plan.optionsJson());var trial=options.path("scenarios").path("trial");
        assertThat(trial.path("executor").asText()).isEqualTo("constant-arrival-rate");
        assertThat(trial.path("rate").asInt()).isEqualTo(3);assertThat(trial.path("duration").asText()).isEqualTo("20s");
        assertThat(trial.path("maxVUs").asInt()).isEqualTo(2);assertThat(trial.path("gracefulStop").asText()).isEqualTo("2s");
        assertThat(options.path("maxRedirects").asInt()).isZero();assertThat(options.path("insecureSkipTLSVerify").asBoolean()).isFalse();
        assertThat(options.path("systemTags").toString()).isEqualTo("[\"status\",\"error_code\",\"scenario\"]");
    }
    @Test void mapsRampAndSpikeStagesWithoutChangingRatesOrWindows() throws Exception {
        for(var profile:List.of(new LoadProfile(LoadProfile.Mode.SINGLE_RUN,LoadProfile.Shape.RAMP,1,List.of(new LoadProfile.Stage(5,5),new LoadProfile.Stage(15,5)),15),
                new LoadProfile(LoadProfile.Mode.SINGLE_RUN,LoadProfile.Shape.SPIKE,1,List.of(new LoadProfile.Stage(5,5),new LoadProfile.Stage(10,5),new LoadProfile.Stage(5,1)),10))) {
            var f=fixture(profile,"export default function() {}");var plan=planner.construct(f.snapshot(),f.ticket(),policy,destination,now);
            var trial=json.readTree(plan.optionsJson()).path("scenarios").path("trial");
            assertThat(trial.path("executor").asText()).isEqualTo("ramping-arrival-rate");
            assertThat(trial.path("startRate").asInt()).isEqualTo(1);
            assertThat(trial.path("stages").size()).isEqualTo(profile.stages().size());
            for(int i=0;i<profile.stages().size();i++) {
                assertThat(trial.path("stages").get(i).path("target").asInt()).isEqualTo(profile.stages().get(i).targetRate());
                assertThat(trial.path("stages").get(i).path("duration").asText()).isEqualTo(profile.stages().get(i).durationSeconds()+"s");
            }
        }
    }
    @Test void restrictsNetworkToOnePinnedAddressAndPortWithoutDnsOrIngress() throws Exception {
        var f=fixture(steady(),"export default function() {}");var plan=planner.construct(f.snapshot(),f.ticket(),policy,destination,now);
        var network=plan.network().getSpec();assertThat(network.getIngress()).isEmpty();assertThat(network.getEgress()).hasSize(1);
        var rule=network.getEgress().getFirst();assertThat(rule.getTo()).hasSize(1);assertThat(rule.getTo().getFirst().getIpBlock().getCidr()).isEqualTo("192.0.2.10/32");
        assertThat(rule.getPorts()).hasSize(1);assertThat(rule.getPorts().getFirst().getPort().getIntValue()).isEqualTo(443);
        var pod=plan.job().getSpec().getTemplate().getSpec();assertThat(pod.getDnsPolicy()).isEqualTo("None");
        assertThat(pod.getHostAliases().getFirst().getIp()).isEqualTo("192.0.2.10");
        assertThat(network.getPodSelector().getMatchLabels()).containsEntry("loadpilot.dev/attempt",f.ticket().attemptId().toString());
    }
    @Test void rejectsExpiredMismatchedAndUnpinnedInputs() throws Exception {
        var f=fixture(steady(),"export default function() {}");
        assertThatThrownBy(()->planner.construct(f.snapshot(),f.ticket(),policy,destination,now.plusSeconds(36))).hasMessage("Insufficient remaining run budget.");
        assertThatThrownBy(()->planner.construct(f.snapshot(),f.ticket(),new K6JobPlan.Policy("runners","example.invalid/k6:latest","runner","sandboxed",32),destination,now)).hasMessage("An immutable runtime image digest is required.");
        assertThatThrownBy(()->planner.construct(f.snapshot(),f.ticket(),policy,new K6JobPlan.PinnedTarget("other.invalid","192.0.2.10",443),now)).hasMessage("Pinned destination differs from the reviewed target origin.");
        for(String ip:List.of("127.0.0.1","169.254.169.254","0.0.0.0","224.0.0.1","::1"))
            assertThatThrownBy(()->planner.construct(f.snapshot(),f.ticket(),policy,new K6JobPlan.PinnedTarget("fixture.invalid",ip,443),now)).hasMessage("Unsafe pinned destination address.");
        assertThatThrownBy(()->planner.construct(f.snapshot(),f.ticket(),policy,new K6JobPlan.PinnedTarget("fixture.invalid","resolve.invalid",443),now)).hasMessage("A literal pinned IP is required; the planner does not resolve DNS.");
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="TEST_K6_EXECUTABLE",matches=".+")
    void nativeK6ReadsGeneratedOptionsWithoutRunningIterations(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        // Only this fixed, owned, no-I/O source may reach native inspect. Never inspect arbitrary saved source here.
        var f=fixture(steady(),"export default function() {}\n");
        var plan=planner.construct(f.snapshot(),f.ticket(),policy,destination,now);
        for(var file:plan.files().getData().entrySet()) Files.writeString(temporary.resolve(file.getKey()),file.getValue(),StandardCharsets.UTF_8);
        var command=new ProcessBuilder(System.getenv("TEST_K6_EXECUTABLE"),"inspect","--execution-requirements","--config",temporary.resolve("options.json").toString(),temporary.resolve("entry.js").toString()).redirectErrorStream(true);
        String systemRoot=System.getenv("SystemRoot");command.environment().clear();
        if(systemRoot!=null) command.environment().put("SystemRoot",systemRoot);
        var process=command.start();
        if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly();throw new AssertionError("Native k6 inspect timed out."); }
        String output=new String(process.getInputStream().readNBytes(65537),StandardCharsets.UTF_8);
        assertThat(output.length()).isLessThanOrEqualTo(65536);
        assertThat(process.exitValue()).as(output).isZero();
        var inspected=json.readTree(output.substring(output.indexOf('{'),output.lastIndexOf('}')+1));
        assertThat(inspected.path("scenarios").path("trial").path("executor").asText()).as(output).isEqualTo("constant-arrival-rate");
        assertThat(inspected.path("scenarios").path("trial").path("rate").asInt()).isEqualTo(3);
        System.out.println("Native k6 accepted generated steady configuration; inspect only, no iterations or HTTP requests.");
    }
    @Test void rejectsSourceDriftAndWrongSnapshotTicket() throws Exception {
        var f=fixture(steady(),"export default function() {}");var i=f.snapshot().inputs();var s=i.scenario().script();
        var changed=new ScenarioApi.Version(s.scenarioId(),s.name(),s.version(),s.source()+"\n// changed",s.sha256(),s.createdBy(),s.createdAt(),true);
        var a=i.scenario();var approval=new ScenarioStore.ApprovedVersion(changed,a.approvalId(),a.validationId(),a.policyVersion(),a.parserVersion(),a.approvedBy(),a.approvedAt());
        var input=new ExperimentSnapshots.Inputs(i.schemaVersion(),i.draftId(),i.draftRevision(),i.mode(),i.target(),approval,i.settings(),i.loadUnit(),i.executor(),i.measurementSeconds(),i.sloOperator(),i.executionBounds(),i.maximumAttemptSeconds(),i.reservedAttemptsPerLoad(),i.loadProfile(),i.measurementRate(),i.sloPolicy());
        var corrupt=new ExperimentSnapshots.Snapshot(f.snapshot().id(),input,f.snapshot().sha256(),"admin",f.snapshot().createdAt(),true,false);
        assertThatThrownBy(()->planner.construct(corrupt,f.ticket(),policy,destination,now)).hasMessage("Approved exact-source integrity failed.");
        var t=f.ticket();var wrong=new RunDispatch.Ticket(t.runId(),UUID.randomUUID(),t.revision(),t.attemptId(),t.shardId(),t.submitOperationId(),t.workerName(),t.maximumSeconds(),t.deadlineAt());
        assertThatThrownBy(()->planner.construct(f.snapshot(),wrong,policy,destination,now)).hasMessage("Ticket does not match the reviewed snapshot and worker identity.");
    }
    @Test void envelopeIsStableAndBindsExactSourceImageDestinationAndApiQuantities() throws Exception {
        String source="// Xin chào\nexport default function() {}\n";var f=fixture(steady(),source);
        var envelopes=new K6PlanEnvelope(json,Validation.buildDefaultValidatorFactory().getValidator());
        var first=envelopes.prepare(f.snapshot(),f.ticket(),policy,destination,now);
        assertThat(envelopes.prepare(f.snapshot(),f.ticket(),policy,destination,now.plusSeconds(1))).isEqualTo(first);
        assertThat(K6PlanEnvelope.hash(first.canonicalJson())).isEqualTo(first.sha256());
        var body=json.readTree(first.canonicalJson());
        assertThat(body.path("plannerVersion").asText()).isEqualTo("job-planner-v6");
        assertThat(body.path("workerClock").path("version").asText()).isEqualTo("worker-clock-v1");
        assertThat(body.path("workerClock").path("submissionStartedAt").asText()).isEqualTo(now.toString());
        assertThat(body.path("requiredSourcePolicy").path("version").asText()).isEqualTo("literal-http-v1");
        assertThat(body.path("requiredSourcePolicy").path("requestTimeoutMs").asInt()).isEqualTo(1000);
        assertThat(body.path("job").path("spec").path("suspend").asBoolean()).isTrue();
        assertThat(body.path("files").path("data").path("scenario.js").asText()).isEqualTo(source);
        assertThat(body.path("job").path("spec").path("template").path("spec").path("containers").get(0).path("resources").path("limits").path("cpu").asText()).isEqualTo("250m");
        assertThat(body.path("submissionReady").asBoolean()).isFalse();
        var changedImage=new K6JobPlan.Policy(policy.namespace(),"example.invalid/k6@sha256:"+"c".repeat(64),policy.serviceAccount(),policy.runtimeClass(),32);
        assertThat(envelopes.prepare(f.snapshot(),f.ticket(),changedImage,destination,now).sha256()).isNotEqualTo(first.sha256());
        assertThat(envelopes.prepare(f.snapshot(),f.ticket(),policy,new K6JobPlan.PinnedTarget(destination.host(),"192.0.2.11",443),now).sha256()).isNotEqualTo(first.sha256());
    }
    @Test void clusterBoundV2HashesConnectionIdentityWithoutChangingTheUnboundGenerationBranch() throws Exception {
        var f=fixture(steady(),"export default function() {}");var envelopes=new K6PlanEnvelope(json,Validation.buildDefaultValidatorFactory().getValidator());
        var legacy=envelopes.prepare(f.snapshot(),f.ticket(),policy,destination,now);
        var binding=new ExecutionClusterClients.Binding(f.snapshot().inputs().target().id(),1,"https://cluster.invalid:6443",List.of("192.0.2.20"),policy.namespace(),List.of("STOP","READ"),"a".repeat(64));
        var bound=envelopes.prepare(f.snapshot(),f.ticket(),policy,destination,binding,now);var body=json.readTree(bound.canonicalJson());
        assertThat(body.path("schemaVersion").asText()).isEqualTo("k6-execution-plan-v2");assertThat(body.path("clusterBinding").path("connectionRevision").asInt()).isEqualTo(1);
        assertThat(bound.canonicalJson()).doesNotContain("BEGIN CERTIFICATE","fixture-token");
        var changed=new ExecutionClusterClients.Binding(binding.targetId(),2,binding.origin(),binding.addresses(),binding.namespace(),binding.operations(),binding.caSha256());
        assertThat(envelopes.prepare(f.snapshot(),f.ticket(),policy,destination,changed,now).sha256()).isNotEqualTo(bound.sha256());
        assertThat(envelopes.prepare(f.snapshot(),f.ticket(),policy,destination,now)).isEqualTo(legacy);assertThat(json.readTree(legacy.canonicalJson()).has("clusterBinding")).isFalse();
    }
}
