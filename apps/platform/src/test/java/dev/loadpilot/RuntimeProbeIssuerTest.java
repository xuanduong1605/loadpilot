package dev.loadpilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.loadpilot.integrations.k6.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RuntimeProbeIssuerTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Instant base=Instant.parse("2026-11-06T00:00:00Z");
    @TempDir Path directory;
    static final class Time extends Clock {Instant now;Time(Instant now){this.now=now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
    final RuntimeProbePlan.Node node=new RuntimeProbePlan.Node("node-a","node-uid");
    final RuntimeProbePlan.Canary canary=new RuntimeProbePlan.Canary("http://probe.invalid:8080","192.0.2.33","/ready","/stall");
    class Context implements AutoCloseable {
        final SignedRuntimeAdmissionTest helper=new SignedRuntimeAdmissionTest();final SignedRuntimeAdmissionTest.Server server=helper.new Server();
        final SignedRuntimeAdmissionTest.Bound bound;final K6PlanEnvelope.Prepared original;final RuntimeProbeArchive archive;final Path archives,reports;final Time time=new Time(base);
        final KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();final SignedRuntimeEvidence verifier;final KubernetesRuntimeState state;final KubernetesProbeCollector collector;final RuntimeProbeIssuer issuer;final UUID id=UUID.randomUUID();
        final EnumMap<RuntimeProbePlan.Phase,RuntimeProbePlan.Prepared> plans=new EnumMap<>(RuntimeProbePlan.Phase.class);final Map<String,JsonNode> deployed=new HashMap<>();
        Context() throws Exception{
            server.missingStatus=404;bound=helper.bound(server,List.of("RUNTIME","PROBE_READ"));original=bound.plan();archives=Files.createTempDirectory(directory,"archive-");reports=Files.createTempDirectory(directory,"reports-");
            archive=new RuntimeProbeArchive(archives,json);String keys=json.writeValueAsString(Map.of("operator",Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())));verifier=new SignedRuntimeEvidence(reports.toString(),keys,json);
            state=new KubernetesRuntimeState(bound.clients(),json,"network-system","cni-agent");collector=new KubernetesProbeCollector(bound.clients(),json,time);issuer=new RuntimeProbeIssuer(archive,collector,state,verifier,reports,"operator",pair.getPrivate(),pair.getPublic(),json,time);
            var nodes=server.reads.get("/api/v1/nodes?limit=33");var direct=nodes.path("items").get(0).deepCopy();((ObjectNode)direct.path("metadata")).put("resourceVersion","1");server.reads.put("/api/v1/nodes/node-a",direct);emptyPods();
            issuer.begin(original,id,List.of(node));
        }
        String podList(){return "/api/v1/namespaces/"+bound.binding().namespace()+"/pods?limit=17";}
        void emptyPods(){var listing=json.createObjectNode();listing.putObject("metadata");listing.putArray("items");server.reads.put(podList(),listing);}
        RuntimeProbePlan.Prepared prepare(RuntimeProbePlan.Phase phase,int second){time.now=base.plusSeconds(second);var prepared=issuer.prepare(original,id,node,canary,"https://fixture.invalid/ready",phase);plans.put(phase,prepared);return prepared;}
        void deploy(RuntimeProbePlan.Prepared prepared) throws Exception{
            var body=json.readTree(prepared.canonicalJson());var phase=RuntimeProbePlan.Phase.valueOf(body.path("phase").asText());Instant created=Instant.parse(body.path("createdAt").asText());String name=body.path("job").path("metadata").path("name").asText(),ns=bound.binding().namespace(),podName=name+"-abc";var paths=KubernetesProbeCollector.paths(body,podName);
            for(int i=0;i<3;i++){String field=List.of("files","network","job").get(i);var value=(ObjectNode)body.path(field).deepCopy();((ObjectNode)value.path("metadata")).put("uid",phase.name()+"-"+field+"-uid").put("resourceVersion","1");server.reads.put(paths.get(i),value);deployed.put(paths.get(i),value.deepCopy());}
            var job=(ObjectNode)server.reads.get(paths.get(2));boolean negative=Set.of(RuntimeProbePlan.Phase.LATE_GUARD,RuntimeProbePlan.Phase.EXPIRED_GUARD,RuntimeProbePlan.Phase.DEADLINE).contains(phase);
            if(phase!=RuntimeProbePlan.Phase.CLOCK)job.putObject("status").putArray("conditions").addObject().put("type",negative?"Failed":"Complete").put("status","True").put("reason",phase==RuntimeProbePlan.Phase.DEADLINE?"DeadlineExceeded":negative?"BackoffLimitExceeded":"CompletionsReached");
            var pod=json.createObjectNode();var meta=pod.putObject("metadata");meta.put("name",podName).put("namespace",ns).put("uid",phase.name()+"-pod-uid").put("resourceVersion","1");meta.set("labels",body.path("job").path("spec").path("template").path("metadata").path("labels"));meta.set("annotations",body.path("job").path("spec").path("template").path("metadata").path("annotations"));meta.putArray("ownerReferences").addObject().put("controller",true).put("kind","Job").put("name",name).put("uid",phase.name()+"-job-uid");
            pod.set("spec",body.path("job").path("spec").path("template").path("spec").deepCopy());((ObjectNode)pod.path("spec")).put("nodeName",node.name());var status=pod.putObject("status");status.put("startTime",created.plusMillis(100).toString()).put("phase",phase==RuntimeProbePlan.Phase.CLOCK?"Running":negative?"Failed":"Succeeded");if(phase==RuntimeProbePlan.Phase.DEADLINE)status.put("reason","DeadlineExceeded");
            var container=status.putArray("containerStatuses").addObject().put("name","k6").put("restartCount",0).put("imageID",body.path("job").path("spec").path("template").path("spec").path("containers").get(0).path("image").asText());
            if(phase==RuntimeProbePlan.Phase.CLOCK){container.putObject("state").putObject("running").put("startedAt",created.plusMillis(100).toString());time.now=created.plusSeconds(1);server.raw.put(paths.get(6),time.now+" LP_PROBE_CLOCK:"+id+":"+node.uid()+":"+time.now.toEpochMilli()+"\n");}
            else{
                long millis=phase==RuntimeProbePlan.Phase.DEADLINE?body.path("job").path("spec").path("template").path("spec").path("activeDeadlineSeconds").asLong()*1000+100:phase==RuntimeProbePlan.Phase.TIMEOUT?3200:500;
                var ended=container.putObject("state").putObject("terminated").put("startedAt",created.plusMillis(100).toString()).put("finishedAt",created.plusMillis(millis).toString()).put("exitCode",phase==RuntimeProbePlan.Phase.DEADLINE?143:negative?108:0);
                ended.put("message",negative?"private-error":new KubernetesProbeCollectorTest().summary(body,phase).toString());time.now=created.plusMillis(millis+500);
            }
            server.reads.put(paths.get(5),pod);deployed.put(paths.get(5),pod.deepCopy());var listing=json.createObjectNode();listing.putObject("metadata");listing.putArray("items").add(pod);server.reads.put(podList(),listing);
        }
        void cleanup(RuntimeProbePlan.Prepared prepared) throws Exception{var body=json.readTree(prepared.canonicalJson());String name=body.path("job").path("metadata").path("name").asText();for(String path:KubernetesProbeCollector.paths(body,name+"-abc")){if(!path.equals("/api/v1/nodes/node-a") && !path.equals(podList())){server.reads.remove(path);server.raw.remove(path);}}emptyPods();}
        void stage(RuntimeProbePlan.Phase phase,int second) throws Exception{var prepared=prepare(phase,second);deploy(prepared);issuer.capture(original,id,prepared);cleanup(prepared);}
        void complete(boolean badOrder) throws Exception{
            stage(RuntimeProbePlan.Phase.BASELINE_BEFORE,1);stage(RuntimeProbePlan.Phase.ALLOWED,3);
            if(badOrder)stage(RuntimeProbePlan.Phase.BASELINE_AFTER,4);stage(RuntimeProbePlan.Phase.BLOCKED,6);if(!badOrder)stage(RuntimeProbePlan.Phase.BASELINE_AFTER,8);
            stage(RuntimeProbePlan.Phase.TIMEOUT,11);stage(RuntimeProbePlan.Phase.VALID_GUARD,16);stage(RuntimeProbePlan.Phase.LATE_GUARD,18);stage(RuntimeProbePlan.Phase.EXPIRED_GUARD,20);stage(RuntimeProbePlan.Phase.DEADLINE,22);stage(RuntimeProbePlan.Phase.CLOCK,47);time.now=base.plusSeconds(60);
        }
        RuntimeProbeIssuer.Published publish(String expected){return issuer.publish(original,id,new ArrayList<>(plans.values()),base.plusSeconds(600),expected);}
        Path report(){return reports.resolve(SignedRuntimeEvidence.profile(original,json)+".json");}
        public void close(){server.close();}
    }
    @Test void completeLiveReadbackSealsSurviveRestartAndPublishAConsumerVerifiedReportWithExplicitCas() throws Exception{
        try(var c=new Context()){
            c.complete(false);Files.writeString(c.report(),"prior-pointer");assertThatThrownBy(()->c.publish(null)).isInstanceOf(IllegalStateException.class);assertThat(Files.readString(c.report())).isEqualTo("prior-pointer");
            String prior=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("prior-pointer".getBytes(StandardCharsets.UTF_8)));var published=c.publish(prior);byte[] envelope=Files.readAllBytes(c.report());
            var verified=c.verifier.load(c.original,c.time.instant());assertThat(verified.report().verificationId()).isEqualTo(c.id);assertThat(verified.report().nodes()).hasSize(1);var observed=verified.report().nodes().getFirst();assertThat(observed.baselineBeforeRequests()).isEqualTo(3);assertThat(observed.baselineAfterRequests()).isEqualTo(3);assertThat(observed.blockedResponses()).isZero();assertThat(observed.deadlineExitCode()).isEqualTo(143);
            var admission=new SignedRuntimeAdmission(c.verifier,c.state,c.time);var grant=admission.verify(c.original);admission.recheck(c.original,grant);assertThat(grant.verificationId()).isEqualTo(published.verificationId());
            var restarted=new RuntimeProbeIssuer(new RuntimeProbeArchive(c.archives,json),c.collector,c.state,c.verifier,c.reports,"operator",c.pair.getPrivate(),c.pair.getPublic(),json,c.time);
            int before=c.server.requests.size();var old=c.plans.get(RuntimeProbePlan.Phase.ALLOWED);assertThat(restarted.capture(c.original,c.id,old).reference().planSha256()).isEqualTo(old.sha256());assertThat(c.server.requests).hasSize(before);
            var same=restarted.publish(c.original,c.id,new ArrayList<>(c.plans.values()),base.plusSeconds(600),null);assertThat(same).isEqualTo(published);assertThat(Files.readAllBytes(c.report())).isEqualTo(envelope);
            assertThat(restarted.prepare(c.original,c.id,node,canary,"https://fixture.invalid/ready",RuntimeProbePlan.Phase.ALLOWED)).isEqualTo(old);assertThat(c.server.requests).allMatch(r->r.startsWith("GET "));
            c.time.now=base.plusSeconds(601);assertThatThrownBy(()->c.publish(null)).isInstanceOf(IllegalStateException.class);assertThat(Files.readAllBytes(c.report())).isEqualTo(envelope);
        }
    }
    @Test void missingDuplicateOutOfOrderOrTamperedSealedCapturesCannotPublish() throws Exception{
        try(var c=new Context()){
            c.complete(true);assertThatThrownBy(()->c.publish(null)).isInstanceOf(IllegalStateException.class);assertThat(c.report()).doesNotExist();
            var missing=new ArrayList<>(c.plans.values());missing.removeLast();assertThatThrownBy(()->c.issuer.publish(c.original,c.id,missing,base.plusSeconds(600),null)).isInstanceOf(IllegalStateException.class);
            var duplicate=new ArrayList<>(c.plans.values());duplicate.set(0,duplicate.get(1));assertThatThrownBy(()->c.issuer.publish(c.original,c.id,duplicate,base.plusSeconds(600),null)).isInstanceOf(IllegalStateException.class);
            var first=c.plans.get(RuntimeProbePlan.Phase.ALLOWED);Files.writeString(c.archives.resolve("seal-"+first.sha256()+".json"),"private-tampered-seal");int before=c.server.requests.size();assertThatThrownBy(()->c.issuer.capture(c.original,c.id,first)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-tampered-seal");assertThat(c.server.requests).hasSize(before);
        }
    }
    @Test void cleanupRequiresBoth404RoundsCompletePodAbsenceAndUnchangedCurrentEnvironment() throws Exception{
        try(var c=new Context()){
            c.complete(false);var p=c.plans.get(RuntimeProbePlan.Phase.BASELINE_BEFORE);var b=json.readTree(p.canonicalJson());String name=b.path("job").path("metadata").path("name").asText();var paths=KubernetesProbeCollector.paths(b,name+"-abc");
            c.server.reads.put(paths.get(2),c.deployed.get(paths.get(2)));assertThatThrownBy(()->c.publish(null)).isInstanceOf(IllegalStateException.class);c.server.reads.remove(paths.get(2));
            var listing=json.createObjectNode();listing.putObject("metadata");listing.putArray("items").add(c.deployed.get(paths.get(5)));c.server.reads.put(c.podList(),listing);assertThatThrownBy(()->c.publish(null)).isInstanceOf(IllegalStateException.class);c.emptyPods();
            var nodes=c.server.reads.get("/api/v1/nodes?limit=33").deepCopy();((ObjectNode)c.server.reads.get("/api/v1/nodes?limit=33").path("items").get(0).path("status").path("nodeInfo")).put("bootID","changed");assertThatThrownBy(()->c.publish(null)).isInstanceOf(IllegalStateException.class);c.server.reads.put("/api/v1/nodes?limit=33",nodes);
            var incomplete=json.createObjectNode();incomplete.putObject("metadata").put("continue","more");incomplete.putArray("items");c.server.reads.put(c.podList(),incomplete);assertThatThrownBy(()->c.publish(null)).isInstanceOf(IllegalStateException.class);assertThat(c.report()).doesNotExist();assertThat(c.server.requests).allMatch(r->r.startsWith("GET "));
        }
    }
    @Test void unsignedArchiveCannotBeSealedAfterResourcesDisappearAndUnknownAbsenceHasNoAuthority() throws Exception{
        try(var c=new Context()){
            var prepared=c.prepare(RuntimeProbePlan.Phase.ALLOWED,1);c.deploy(prepared);var service=new RuntimeProbeEvidenceService(new RuntimeProbePlan(json),c.archive,c.collector,c.time);var known=service.capture(c.original,prepared);c.cleanup(prepared);
            assertThatThrownBy(()->c.issuer.capture(c.original,c.id,prepared)).isInstanceOf(IllegalStateException.class);assertThat(c.archive.checkpoint("seal",prepared.sha256())).isEmpty();
            var fake=new RuntimeProbeArchive.Archived(new RuntimeProbeArchive.Reference(prepared.sha256(),"a".repeat(64)),known.artifactJson());assertThatThrownBy(()->c.collector.confirmAbsent(c.original,prepared,fake)).isInstanceOf(IllegalStateException.class);assertThat(c.report()).doesNotExist();
        }
    }
}
