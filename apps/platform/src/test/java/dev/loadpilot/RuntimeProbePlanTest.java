package dev.loadpilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.integrations.k6.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RuntimeProbePlanTest {
    final ObjectMapper json=new ObjectMapper();
    final RuntimeProbePlan builder=new RuntimeProbePlan(json);
    final UUID id=UUID.randomUUID();
    final RuntimeProbePlan.Node node=new RuntimeProbePlan.Node("node-a","node-uid");
    final RuntimeProbePlan.Canary canary=new RuntimeProbePlan.Canary("http://probe.invalid:8080","192.0.2.33","/ready","/stall");
    final Instant now=Instant.parse("2026-11-06T00:00:00Z");
    K6PlanEnvelope.Prepared original() throws Exception{return new KubernetesWorkerPreparerTest().plan().plan();}
    RuntimeProbePlan.Prepared plan(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Phase phase){return builder.construct(original,id,node,canary,"https://fixture.invalid/ready",phase,now);}
    @Test void allFixedPhasesRetainSecurityResourceDeadlineBoundsWithoutCopyingSubmittedSource() throws Exception {
        var original=original();var reviewed=json.readTree(original.canonicalJson());var expected=reviewed.path("job").path("spec").path("template").path("spec");Set<String> names=new HashSet<>();
        for(var phase:RuntimeProbePlan.Phase.values()){
            var plan=plan(original,phase);var body=builder.verify(original,plan);var job=body.path("job");var pod=job.path("spec").path("template").path("spec");
            assertThat(names.add(job.path("metadata").path("name").asText())).isTrue();assertThat(job.path("spec").path("suspend").asBoolean()).isFalse();assertThat(job.path("spec").path("backoffLimit").asInt(-1)).isZero();
            assertThat(job.path("spec").path("activeDeadlineSeconds")).isEqualTo(reviewed.path("job").path("spec").path("activeDeadlineSeconds"));
            for(String field:List.of("activeDeadlineSeconds","terminationGracePeriodSeconds","securityContext","automountServiceAccountToken","serviceAccountName","runtimeClassName","hostNetwork","hostPID","hostIPC","dnsPolicy","dnsConfig"))assertThat(pod.path(field)).as(field).isEqualTo(expected.path(field));
            for(String field:List.of("image","resources","securityContext","env","volumeMounts"))assertThat(pod.path("containers").get(0).path(field)).as(field).isEqualTo(expected.path("containers").get(0).path(field));
            assertThat(pod.hasNonNull("nodeName")).isFalse();assertThat(pod.path("affinity").path("nodeAffinity").path("requiredDuringSchedulingIgnoredDuringExecution").path("nodeSelectorTerms").get(0).path("matchFields").get(0).path("values").get(0).asText()).isEqualTo("node-a");
            var files=body.path("files");assertThat(files.path("immutable").asBoolean()).isTrue();assertThat(files.path("data").path("scenario.js").asText()).doesNotContain("http.get('https://fixture.invalid');");
            assertThat(body.path("requestedHttpCalls").asInt()).isEqualTo(Set.of(RuntimeProbePlan.Phase.ALLOWED,RuntimeProbePlan.Phase.BLOCKED,RuntimeProbePlan.Phase.TIMEOUT,RuntimeProbePlan.Phase.BASELINE_BEFORE,RuntimeProbePlan.Phase.BASELINE_AFTER).contains(phase)?3:0);
            assertThat(body.path("sourcePlanSha256").asText()).isEqualTo(original.sha256());assertThat(body.path("profileSha256").asText()).isEqualTo(SignedRuntimeEvidence.profile(original,json));
        }
    }
    @Test void phasePoliciesUseOnlyTheExplicitTargetOrCanaryAndCannotWidenOtherProbePhases() throws Exception {
        var original=original();Set<JsonNode> selectors=new HashSet<>();
        for(var phase:RuntimeProbePlan.Phase.values()){
            var body=builder.verify(original,plan(original,phase));var spec=body.path("network").path("spec");assertThat(spec.path("ingress").isEmpty()).isTrue();assertThat(selectors.add(spec.path("podSelector"))).isTrue();
            var egress=spec.path("egress");boolean target=Set.of(RuntimeProbePlan.Phase.ALLOWED,RuntimeProbePlan.Phase.BLOCKED).contains(phase);
            boolean ownedCanary=Set.of(RuntimeProbePlan.Phase.BASELINE_BEFORE,RuntimeProbePlan.Phase.BASELINE_AFTER,RuntimeProbePlan.Phase.TIMEOUT).contains(phase);
            if(target || ownedCanary){assertThat(egress).hasSize(1);assertThat(egress.get(0).path("to").get(0).path("ipBlock").path("cidr").asText()).isEqualTo(target?"192.0.2.10/32":"192.0.2.33/32");assertThat(egress.get(0).path("ports").get(0).path("port").asInt()).isEqualTo(target?443:8080);}
            else assertThat(egress.isEmpty()).isTrue();
            assertThat(spec.toString()).doesNotContain("0.0.0.0/0");assertThat(spec.path("podSelector").path("matchLabels")).isEqualTo(body.path("job").path("spec").path("template").path("metadata").path("labels"));
        }
    }
    @Test void tamperedProgramsSpecsOrNodeIdentityCannotBeAuthorizedByRehashing() throws Exception {
        var original=original();var plan=plan(original,RuntimeProbePlan.Phase.ALLOWED);assertThat(plan(original,RuntimeProbePlan.Phase.ALLOWED)).isEqualTo(plan);
        for(String change:List.of("code","image","uid","phase","extra","policy")){
            var body=(ObjectNode)json.readTree(plan.canonicalJson());
            switch(change){
                case "code"->((ObjectNode)body.path("files").path("data")).put("scenario.js","private-value");
                case "image"->((ObjectNode)body.path("job").path("spec").path("template").path("spec").path("containers").get(0)).put("image","foreign.invalid/image");
                case "uid"->((ObjectNode)body.path("node")).put("uid","foreign-node");case "phase"->body.put("phase","BLOCKED");case "extra"->body.put("extra","private-value");
                case "policy"->((ObjectNode)body.path("network").path("spec")).putArray("egress");
            }
            String text=body.toString();assertThatThrownBy(()->builder.verify(original,new RuntimeProbePlan.Prepared(text,K6PlanEnvelope.hash(text)))).as(change).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-value");
        }
    }
    @Test void unsafeAmbiguousCanariesAndInsufficientTimeoutHeadroomAreRejectedBeforeAnyIo() throws Exception {
        var original=original();
        for(String ip:List.of("127.0.0.1","169.254.169.254","0.0.0.0","100.100.100.200","::1","ff02::1","resolve.invalid"))
            assertThatThrownBy(()->builder.construct(original,id,node,new RuntimeProbePlan.Canary(canary.origin(),ip,"/ready","/stall"),"https://fixture.invalid",RuntimeProbePlan.Phase.TIMEOUT,now)).isInstanceOf(IllegalArgumentException.class);
        for(String origin:List.of("http://user:private-value@probe.invalid:8080","file:///private-value","http://probe.invalid:8080?private-value","http://probe.invalid:8080/#private-value"))
            assertThatThrownBy(()->builder.construct(original,id,node,new RuntimeProbePlan.Canary(origin,canary.ip(),"/ready","/stall"),"https://fixture.invalid",RuntimeProbePlan.Phase.TIMEOUT,now)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-value");
        assertThatThrownBy(()->builder.construct(original,id,node,new RuntimeProbePlan.Canary("https://fixture.invalid","192.0.2.10","/ready","/stall"),"https://fixture.invalid",RuntimeProbePlan.Phase.BLOCKED,now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->builder.construct(original,id,node,canary,"https://foreign.invalid",RuntimeProbePlan.Phase.ALLOWED,now)).isInstanceOf(IllegalArgumentException.class);
        var body=(ObjectNode)json.readTree(original.canonicalJson());((ObjectNode)body.path("requiredSourcePolicy")).put("requestTimeoutMs",8000);String text=body.toString();var limited=new K6PlanEnvelope.Prepared(text,K6PlanEnvelope.hash(text));
        assertThatThrownBy(()->plan(limited,RuntimeProbePlan.Phase.TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
        var foreign=(ObjectNode)json.readTree(original.canonicalJson());((ObjectNode)foreign.path("policy")).put("namespace","foreign");String wrong=foreign.toString();
        assertThatThrownBy(()->plan(new K6PlanEnvelope.Prepared(wrong,K6PlanEnvelope.hash(wrong)),RuntimeProbePlan.Phase.ALLOWED)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void archivePreservesIntentBeforeArtifactsAndDetectsCorruptionOrConflictingReplay(@TempDir Path directory) throws Exception {
        var original=original();var plan=plan(original,RuntimeProbePlan.Phase.ALLOWED);var archive=new RuntimeProbeArchive(directory,json);byte[] artifact="{\"fixture\":\"verified-sanitized-receipt\"}".getBytes(StandardCharsets.UTF_8);
        archive.prepare(original,plan);assertThat(directory.resolve(plan.sha256()+".plan.json")).exists();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->archive.store(original,plan,artifact));var b=pool.submit(()->new RuntimeProbeArchive(directory,json).store(original,plan,artifact));var ref=a.get(5,TimeUnit.SECONDS);assertThat(b.get(5,TimeUnit.SECONDS)).isEqualTo(ref);assertThat(archive.read(original,plan,ref)).isEqualTo(artifact);
            assertThatThrownBy(()->archive.read(original,plan,new RuntimeProbeArchive.Reference(plan.sha256(),"c".repeat(64)))).isInstanceOf(IllegalStateException.class);
            Files.writeString(directory.resolve(ref.artifactSha256()+".artifact"),"corrupted");assertThatThrownBy(()->archive.read(original,plan,ref)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->archive.store(original,plan,artifact)).isInstanceOf(IllegalStateException.class);
            Files.writeString(directory.resolve(plan.sha256()+".plan.json"),"conflicting");assertThatThrownBy(()->archive.prepare(original,plan)).isInstanceOf(IllegalStateException.class);
            try(var files=Files.list(directory)){assertThat(files.map(p->p.getFileName().toString()).toList()).noneMatch(n->n.startsWith(".pending-"));}
        }finally{pool.shutdownNow();}
    }
    @Test @EnabledIfEnvironmentVariable(named="TEST_K6_EXECUTABLE",matches=".+")
    void nativeEngineInspectsAllFixedProbeProgramsWithoutRunningAnyIterationOrHttp(@TempDir Path directory) throws Exception {
        var original=original();
        for(var phase:RuntimeProbePlan.Phase.values()){
            var body=builder.verify(original,plan(original,phase));for(var file:body.path("files").path("data").properties())Files.writeString(directory.resolve(file.getKey()),file.getValue().asText());
            var command=new ProcessBuilder(System.getenv("TEST_K6_EXECUTABLE"),"inspect","--execution-requirements","--config",directory.resolve("options.json").toString(),directory.resolve("entry.js").toString()).redirectErrorStream(true);
            String systemRoot=System.getenv("SystemRoot");command.environment().clear();if(systemRoot!=null)command.environment().put("SystemRoot",systemRoot);var process=command.start();
            try{if(!process.waitFor(10,TimeUnit.SECONDS))throw new AssertionError("Fixed probe inspect timed out");String output=new String(process.getInputStream().readNBytes(65537),StandardCharsets.UTF_8);assertThat(output.length()).isLessThanOrEqualTo(65536);assertThat(process.exitValue()).as(phase+": "+output).isZero();}
            finally{if(process.isAlive())process.destroyForcibly();}
        }
        System.out.println("Native k6 inspected all10 fixed probe programs; no iterations, target/canary requests or deployed probes.");
    }
    @Test @EnabledIfEnvironmentVariable(named="TEST_NODE_EXECUTABLE",matches=".+")
    void fixedProgramsReportEveryHttpResponseIncluding500AndExactTimeoutsInAnIsolatedVm(@TempDir Path directory) throws Exception {
        // Only generated owned programs enter this VM. http.get and time are synthetic; there is no network capability.
        Files.writeString(directory.resolve("verify.cjs"),"""
            const fs=require('node:fs'),vm=require('node:vm');const p=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
            const metrics={};let clock=100000,calls=0;
            class Counter{constructor(n){this.n=n;}add(v){metrics[this.n]??={values:{count:0}};metrics[this.n].values.count+=v;}}
            class Trend{constructor(n){this.n=n;}add(v){let m=metrics[this.n]??={values:{min:v,max:v}};m.values.min=Math.min(m.values.min,v);m.values.max=Math.max(m.values.max,v);}}
            const context={metrics,Counter,Trend,sleep:()=>{},Date:{now:()=>clock},http:{get:()=>{calls++;clock+=p.mode==='timeout'?1000:10;return p.mode==='timeout'?{status:0,error_code:1050}:p.mode==='http500'?{status:500,error_code:1500}:{status:200,error_code:0};}}};
            const source=p.program.replace(/^import.*$/gm,'').replace('export default function()','function iteration()');
            const summary=p.entry.slice(p.entry.indexOf('export function handleSummary')).replace('export function','function');
            vm.createContext(context);vm.runInContext(source+'\\n'+summary+'\\nthis.result=()=>{iteration();return handleSummary({metrics})};',context,{timeout:1000});
            const files=context.result();process.stdout.write(JSON.stringify({calls,summary:JSON.parse(Object.values(files)[0])}));
            """);
        var original=original();
        for(String mode:List.of("ok","http500","timeout")){
            var phase=mode.equals("timeout")?RuntimeProbePlan.Phase.TIMEOUT:RuntimeProbePlan.Phase.BLOCKED;var body=builder.verify(original,plan(original,phase));
            Files.writeString(directory.resolve("input.json"),json.writeValueAsString(Map.of("program",body.path("files").path("data").path("scenario.js").asText(),"entry",body.path("files").path("data").path("entry.js").asText(),"mode",mode)));
            var command=new ProcessBuilder(System.getenv("TEST_NODE_EXECUTABLE"),directory.resolve("verify.cjs").toString(),directory.resolve("input.json").toString()).redirectErrorStream(true);String systemRoot=System.getenv("SystemRoot");command.environment().clear();if(systemRoot!=null)command.environment().put("SystemRoot",systemRoot);var process=command.start();
            try{if(!process.waitFor(5,TimeUnit.SECONDS))throw new AssertionError("Owned probe VM timed out");String output=new String(process.getInputStream().readNBytes(16385),StandardCharsets.UTF_8);assertThat(process.exitValue()).as(output).isZero();var result=json.readTree(output);assertThat(result.path("calls").asInt()).isEqualTo(3);var summary=result.path("summary");assertThat(summary.path("attempts").asInt()).isEqualTo(3);assertThat(summary.path("responses").asInt()).isEqualTo(mode.equals("timeout")?0:3);assertThat(summary.path("successes").asInt()).isEqualTo(mode.equals("ok")?3:0);assertThat(summary.path("timeouts").asInt()).isEqualTo(mode.equals("timeout")?3:0);assertThat(summary.path("maximumElapsedMs").asInt()).isEqualTo(mode.equals("timeout")?1000:10);}
            finally{if(process.isAlive())process.destroyForcibly();}
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="TEST_K6_EXECUTABLE",matches=".+")
    void nativeFixedClockProgramEmitsTheCollectorsExactRawProtocolWithoutHttp(@TempDir Path directory) throws Exception {
        var original=original();var current=builder.construct(original,id,node,canary,"https://fixture.invalid/ready",RuntimeProbePlan.Phase.CLOCK,Instant.now());var body=builder.verify(original,current);var files=body.path("files").path("data");assertThat(files.path("scenario.js").asText()).doesNotContain("http.get");
        for(var file:files.properties()){
            String code=file.getValue().asText();if(file.getKey().equals("entry.js"))code=code.replace(json.writeValueAsString(RuntimeProbePlan.MESSAGE_PATH),json.writeValueAsString(directory.resolve("summary.json").toString()));Files.writeString(directory.resolve(file.getKey()),code);
        }
        var command=new ProcessBuilder(System.getenv("TEST_K6_EXECUTABLE"),"--log-format=raw","--log-output=stdout","run","--quiet","--no-usage-report","--config",directory.resolve("options.json").toString(),directory.resolve("entry.js").toString()).redirectErrorStream(true);String systemRoot=System.getenv("SystemRoot");command.environment().clear();if(systemRoot!=null)command.environment().put("SystemRoot",systemRoot);var process=command.start();
        try{
            if(!process.waitFor(10,TimeUnit.SECONDS))throw new AssertionError("Owned clock fixture timed out");String output=new String(process.getInputStream().readNBytes(16385),StandardCharsets.UTF_8);assertThat(output.length()).isLessThanOrEqualTo(16384);assertThat(process.exitValue()).as(output).isZero();
            var samples=output.lines().filter(line->!line.isBlank()).toList();String prefix="LP_PROBE_CLOCK:"+id+":node-uid:";assertThat(samples).isNotEmpty().allMatch(line->line.startsWith(prefix) && line.substring(prefix.length()).matches("[0-9]{13}"));assertThat(directory.resolve("summary.json")).exists();
        }finally{if(process.isAlive())process.destroyForcibly();}
        System.out.println("Native fixed clock probe ran4s with no HTTP; raw protocol and owned temporary summary verified.");
    }
}
