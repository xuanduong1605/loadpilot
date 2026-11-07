package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URI;
import java.security.*;
import java.time.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Explicit out-of-band operator issuer. Never registered in the web application or exposed to AI/browser input. */
public final class RuntimeProbeIssuer {
    public record Baseline(String version,UUID probeId,String sourcePlanSha256,String profileSha256,String stateSha256,Instant createdAt,List<RuntimeProbePlan.Node> nodes) {}
    private record Seal(String version,String baselineSha256,String planSha256,String artifactSha256) {}
    private record Sealed(String keyId,String payload,String signature) {}
    public record Published(UUID verificationId,String profileSha256,String envelopeSha256,Instant validUntil) {}
    private final RuntimeProbeArchive archive;
    private final RuntimeProbePlan plans;
    private final KubernetesProbeCollector collector;
    private final KubernetesRuntimeState state;
    private final SignedRuntimeEvidence consumer;
    private final Path output;
    private final String keyId;
    private final PrivateKey key;
    private final PublicKey publicKey;
    private final ObjectMapper json;
    private final Clock clock;

    public RuntimeProbeIssuer(RuntimeProbeArchive archive,KubernetesProbeCollector collector,KubernetesRuntimeState state,SignedRuntimeEvidence consumer,
                              Path output,String keyId,PrivateKey key,PublicKey publicKey,ObjectMapper json,Clock clock){
        try{
            if(keyId==null || !keyId.matches("[A-Za-z0-9_-]{1,64}") || key==null || publicKey==null || !consumer.acceptsIssuer(keyId,publicKey,output))throw denied();
            this.archive=archive;this.plans=new RuntimeProbePlan(json);this.collector=collector;this.state=state;this.consumer=consumer;this.output=output.toAbsolutePath().normalize();
            this.keyId=keyId;this.key=key;this.publicKey=publicKey;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.clock=clock;
            byte[] challenge="loadpilot-issuer-key-pair-v1".getBytes(StandardCharsets.US_ASCII);var verifier=Signature.getInstance("Ed25519");verifier.initVerify(publicKey);verifier.update(challenge);if(!verifier.verify(sign(challenge)))throw denied();
        }catch(Exception invalid){throw denied();}
    }
    public Baseline begin(K6PlanEnvelope.Prepared original,UUID id,List<RuntimeProbePlan.Node> nodes){
        try{
            if(id==null || nodes==null || nodes.isEmpty() || nodes.size()>32)throw denied();List<RuntimeProbePlan.Node> ordered=nodes.stream().sorted(Comparator.comparing(RuntimeProbePlan.Node::name)).toList();
            if(new HashSet<>(ordered.stream().map(RuntimeProbePlan.Node::name).toList()).size()!=ordered.size() || new HashSet<>(ordered.stream().map(RuntimeProbePlan.Node::uid).toList()).size()!=ordered.size())throw denied();
            for(var node:ordered)if(!SignedRuntimeEvidence.name(node.name()) || !SignedRuntimeEvidence.uid(node.uid()))throw denied();
            var existing=archive.checkpoint("baseline",id.toString());
            if(existing.isPresent()){var baseline=baseline(original,id);if(!baseline.nodes().equals(ordered))throw denied();requireState(original,baseline);return baseline;}
            String first=state.readForNodes(original,ordered),last=state.readForNodes(original,ordered);if(!first.equals(last) || !SignedRuntimeEvidence.sha(first))throw denied();
            var baseline=new Baseline("runtime-probe-baseline-v1",id,original.sha256(),SignedRuntimeEvidence.profile(original,json),first,clock.instant(),ordered);
            archive.checkpoint("baseline",id.toString(),seal("baseline",baseline));return baseline(original,id);
        }catch(Exception invalid){throw denied();}
    }
    public RuntimeProbePlan.Prepared prepare(K6PlanEnvelope.Prepared original,UUID id,RuntimeProbePlan.Node node,RuntimeProbePlan.Canary canary,String allowedUrl,RuntimeProbePlan.Phase phase){
        try{
            var baseline=baseline(original,id);if(!baseline.nodes().contains(node) || phase==null)throw denied();String index=planIndex(id,node,phase);
            var existing=archive.checkpoint("plan",index);RuntimeProbePlan.Prepared prepared;
            if(existing.isPresent())prepared=open("plan",existing.get(),RuntimeProbePlan.Prepared.class);
            else{prepared=plans.construct(original,id,node,canary,allowedUrl,phase,clock.instant());archive.checkpoint("plan",index,seal("plan",prepared));}
            var body=plans.verify(original,prepared);if(!json.valueToTree(canary).equals(body.path("canary")) || !URI.create(allowedUrl).toASCIIString().equals(body.path("allowedUrl").asText()))throw denied();
            archive.prepare(original,prepared);probe(original,baseline,prepared);return prepared;
        }catch(Exception invalid){throw denied();}
    }
    public RuntimeProbePlan.Prepared prepared(K6PlanEnvelope.Prepared original,UUID id,RuntimeProbePlan.Node node,RuntimeProbePlan.Phase phase){
        try{var baseline=baseline(original,id);var prepared=open("plan",archive.checkpoint("plan",planIndex(id,node,phase)).orElseThrow(),RuntimeProbePlan.Prepared.class);probe(original,baseline,prepared);archive.requirePrepared(original,prepared);return prepared;}catch(Exception invalid){throw denied();}
    }
    /** Only a live verified capture can acquire its first seal. Cached unsigned numeric files cannot be upgraded to proof. */
    public RuntimeProbeArchive.Archived capture(K6PlanEnvelope.Prepared original,UUID id,RuntimeProbePlan.Prepared prepared){
        try{
            var baseline=baseline(original,id);var body=probe(original,baseline,prepared);archive.requirePrepared(original,prepared);
            if(archive.checkpoint("seal",prepared.sha256()).isPresent())return authenticated(original,baseline,prepared);
            // A crash after archival but before sealing requires a matching live readback; no unsigned replay is trusted.
            var captured=collector.collect(original,prepared);var stored=archive.capture(original,prepared,captured);
            archive.checkpoint("seal",prepared.sha256(),seal("capture",new Seal("runtime-probe-seal-v1",baselineHash(baseline),prepared.sha256(),stored.reference().artifactSha256())));
            return authenticated(original,baseline,prepared);
        }catch(Exception invalid){throw denied();}
    }
    public Published publish(K6PlanEnvelope.Prepared original,UUID id,List<RuntimeProbePlan.Prepared> prepared,Instant validUntil,String expectedCurrentSha256){
        try{
            var baseline=baseline(original,id);if(prepared==null || prepared.size()!=baseline.nodes().size()*RuntimeProbePlan.Phase.values().length)throw denied();
            Map<String,EnumMap<RuntimeProbePlan.Phase,RuntimeProbePlan.Prepared>> groups=new TreeMap<>();Map<String,JsonNode> captures=new HashMap<>();
            JsonNode canary=null;String allowed=null;
            for(var item:prepared){
                var body=probe(original,baseline,item);var p=RuntimeProbePlan.Phase.valueOf(body.path("phase").asText());String node=body.path("node").path("name").asText();
                if(groups.computeIfAbsent(node,n->new EnumMap<>(RuntimeProbePlan.Phase.class)).put(p,item)!=null)throw denied();
                if(canary==null){canary=body.path("canary");allowed=body.path("allowedUrl").asText();}else if(!canary.equals(body.path("canary")) || !allowed.equals(body.path("allowedUrl").asText()))throw denied();
                captures.put(item.sha256(),json.readTree(authenticated(original,baseline,item).artifactJson()));
            }
            for(var node:baseline.nodes()){
                var phases=groups.get(node.name());if(phases==null || phases.size()!=RuntimeProbePlan.Phase.values().length)throw denied();
                var before=captures.get(phases.get(RuntimeProbePlan.Phase.BASELINE_BEFORE).sha256());var blocked=captures.get(phases.get(RuntimeProbePlan.Phase.BLOCKED).sha256());var after=captures.get(phases.get(RuntimeProbePlan.Phase.BASELINE_AFTER).sha256());
                var blockedPlan=plans.verify(original,phases.get(RuntimeProbePlan.Phase.BLOCKED));var afterPlan=plans.verify(original,phases.get(RuntimeProbePlan.Phase.BASELINE_AFTER));
                if(end(before).isAfter(start(blocked)) || end(blocked).isAfter(start(after)) || Instant.parse(before.path("collectedAt").asText()).isAfter(Instant.parse(blockedPlan.path("createdAt").asText())) || Instant.parse(blocked.path("collectedAt").asText()).isAfter(Instant.parse(afterPlan.path("createdAt").asText())))throw denied();
            }
            // Reobserve every known resource now, even on publish replay. An old absence receipt is not current absence.
            Map<String,String> absenceHashes=new HashMap<>();
            for(var item:prepared){var known=authenticated(original,baseline,item);var absent=collector.confirmAbsent(original,item,known);var ref=archive.store(original,item,absent.artifactJson().getBytes(StandardCharsets.UTF_8));absenceHashes.put(item.sha256(),ref.artifactSha256());}
            requireState(original,baseline);
            byte[] envelope;
            var saved=archive.checkpoint("report",id.toString());
            if(saved.isPresent()){
                envelope=saved.get();var verified=consumer.verifyEnvelope(original,envelope,clock.instant());
                if(!verified.report().verificationId().equals(id) || !verified.report().stateSha256().equals(baseline.stateSha256()) || !verified.report().validUntil().equals(validUntil))throw denied();
            }else{
                List<SignedRuntimeEvidence.NodeProbe> nodes=new ArrayList<>();
                for(var node:baseline.nodes()){
                    var phases=groups.get(node.name());Map<String,Object> bundle=new TreeMap<>();
                    for(var p:RuntimeProbePlan.Phase.values()){
                        var item=phases.get(p);var known=authenticated(original,baseline,item);bundle.put(p.name(),Map.of("planSha256",item.sha256(),"captureSha256",known.reference().artifactSha256(),"absenceSha256",absenceHashes.get(item.sha256()),"sealSha256",sha(archive.checkpoint("seal",item.sha256()).orElseThrow())));
                    }
                    var anchor=phases.get(RuntimeProbePlan.Phase.BASELINE_BEFORE);var artifact=archive.store(original,anchor,json.writeValueAsBytes(bundle));
                    nodes.add(node(original,node,phases,captures,artifact.artifactSha256()));
                }
                var report=new SignedRuntimeEvidence.Report(SignedRuntimeEvidence.VERSION,id,baseline.profileSha256(),baseline.stateSha256(),clock.instant(),validUntil,List.copyOf(nodes));
                byte[] payload=json.writeValueAsBytes(report);envelope=json.writeValueAsBytes(new SignedRuntimeEvidence.Envelope(keyId,Base64.getEncoder().encodeToString(payload),Base64.getEncoder().encodeToString(sign(payload))));
                consumer.verifyEnvelope(original,envelope,clock.instant());archive.checkpoint("report",id.toString(),envelope);
            }
            var verified=consumer.verifyEnvelope(original,envelope,clock.instant());requireState(original,baseline);
            install(baseline.profileSha256(),envelope,expectedCurrentSha256);
            return new Published(id,baseline.profileSha256(),sha(envelope),verified.report().validUntil());
        }catch(Exception invalid){throw denied();}
    }
    private SignedRuntimeEvidence.NodeProbe node(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Node node,EnumMap<RuntimeProbePlan.Phase,RuntimeProbePlan.Prepared> phases,Map<String,JsonNode> captures,String artifact) throws Exception {
        var clockData=captures.get(phases.get(RuntimeProbePlan.Phase.CLOCK).sha256());var deadline=captures.get(phases.get(RuntimeProbePlan.Phase.DEADLINE).sha256());var timeout=captures.get(phases.get(RuntimeProbePlan.Phase.TIMEOUT).sha256());
        var body=json.readTree(original.canonicalJson());var spec=body.path("job").path("spec").path("template").path("spec");
        String image=clockData.path("imageId").asText();for(var value:phases.values())if(!image.equals(captures.get(value.sha256()).path("imageId").asText()))throw denied();
        return new SignedRuntimeEvidence.NodeProbe(node.name(),node.uid(),image,artifact,Instant.parse(clockData.path("controllerBefore").asText()),Instant.parse(clockData.path("controllerAfter").asText()),clockData.path("workerEpochMillis").asLong(),
                summary(phases,captures,RuntimeProbePlan.Phase.ALLOWED,"successes"),summary(phases,captures,RuntimeProbePlan.Phase.BASELINE_BEFORE,"successes"),summary(phases,captures,RuntimeProbePlan.Phase.BASELINE_AFTER,"successes"),summary(phases,captures,RuntimeProbePlan.Phase.BLOCKED,"attempts"),summary(phases,captures,RuntimeProbePlan.Phase.BLOCKED,"responses"),
                body.path("requiredSourcePolicy").path("requestTimeoutMs").asInt(),(long)Math.ceil(timeout.path("summary").path("maximumElapsedMs").asDouble()),timeout.path("summary").path("timeouts").asInt(),
                exit(captures.get(phases.get(RuntimeProbePlan.Phase.LATE_GUARD).sha256())),exit(captures.get(phases.get(RuntimeProbePlan.Phase.EXPIRED_GUARD).sha256())),summary(phases,captures,RuntimeProbePlan.Phase.VALID_GUARD,"iterations"),0,
                spec.path("activeDeadlineSeconds").asInt(),spec.path("terminationGracePeriodSeconds").asInt(),Instant.parse(deadline.path("podStartedAt").asText()),end(deadline),deadline.path("podReason").asText(),exit(deadline));
    }
    private int summary(EnumMap<RuntimeProbePlan.Phase,RuntimeProbePlan.Prepared> phases,Map<String,JsonNode> captures,RuntimeProbePlan.Phase phase,String field){return captures.get(phases.get(phase).sha256()).path("summary").path(field).asInt();}
    private static Instant start(JsonNode capture){return Instant.parse(capture.path("containerState").path("terminated").path("startedAt").asText());}
    private static Instant end(JsonNode capture){return Instant.parse(capture.path("containerState").path("terminated").path("finishedAt").asText());}
    private static int exit(JsonNode capture){return capture.path("containerState").path("terminated").path("exitCode").asInt(-1);}
    private JsonNode probe(K6PlanEnvelope.Prepared original,Baseline baseline,RuntimeProbePlan.Prepared prepared){
        var body=plans.verify(original,prepared);if(!baseline.probeId().toString().equals(body.path("probeId").asText()) || !baseline.profileSha256().equals(body.path("profileSha256").asText()) || Instant.parse(body.path("createdAt").asText()).isBefore(baseline.createdAt()))throw denied();
        try{var node=json.treeToValue(body.path("node"),RuntimeProbePlan.Node.class);var phase=RuntimeProbePlan.Phase.valueOf(body.path("phase").asText());if(!baseline.nodes().contains(node) || !prepared.equals(open("plan",archive.checkpoint("plan",planIndex(baseline.probeId(),node,phase)).orElseThrow(),RuntimeProbePlan.Prepared.class)))throw denied();}catch(Exception invalid){throw denied();}return body;
    }
    private Baseline baseline(K6PlanEnvelope.Prepared original,UUID id){
        try{
            var baseline=open("baseline",archive.checkpoint("baseline",id.toString()).orElseThrow(),Baseline.class);
            if(!"runtime-probe-baseline-v1".equals(baseline.version()) || !id.equals(baseline.probeId()) || !original.sha256().equals(baseline.sourcePlanSha256()) || !SignedRuntimeEvidence.profile(original,json).equals(baseline.profileSha256()) || !SignedRuntimeEvidence.sha(baseline.stateSha256()) || baseline.createdAt().isAfter(clock.instant()) || clock.instant().isAfter(baseline.createdAt().plusSeconds(900)))throw denied();return baseline;
        }catch(Exception invalid){throw denied();}
    }
    private RuntimeProbeArchive.Archived authenticated(K6PlanEnvelope.Prepared original,Baseline baseline,RuntimeProbePlan.Prepared prepared){
        try{
            probe(original,baseline,prepared);var known=archive.existing(original,prepared).orElseThrow();var seal=open("capture",archive.checkpoint("seal",prepared.sha256()).orElseThrow(),Seal.class);
            if(!"runtime-probe-seal-v1".equals(seal.version()) || !baselineHash(baseline).equals(seal.baselineSha256()) || !prepared.sha256().equals(seal.planSha256()) || !known.reference().artifactSha256().equals(seal.artifactSha256()))throw denied();
            var body=json.readTree(known.artifactJson());Instant observed=body.path("controllerBefore").isTextual()?Instant.parse(body.path("controllerBefore").asText()):Instant.parse(body.path("podStartedAt").asText());if(observed.isBefore(baseline.createdAt()) || observed.isAfter(clock.instant()))throw denied();return known;
        }catch(Exception invalid){throw denied();}
    }
    private void requireState(K6PlanEnvelope.Prepared original,Baseline baseline){String first=state.readForNodes(original,baseline.nodes()),last=state.readForNodes(original,baseline.nodes());if(!first.equals(last) || !first.equals(baseline.stateSha256()))throw denied();}
    private String baselineHash(Baseline baseline) throws Exception{return sha(json.writeValueAsBytes(baseline));}
    private static String planIndex(UUID id,RuntimeProbePlan.Node node,RuntimeProbePlan.Phase phase){return K6PlanEnvelope.hash(id+":"+node.uid()+":"+phase.name());}
    private byte[] seal(String purpose,Object value) throws Exception {byte[] payload=json.writeValueAsBytes(value);return json.writeValueAsBytes(new Sealed(keyId,Base64.getEncoder().encodeToString(payload),Base64.getEncoder().encodeToString(sign(domain(purpose,payload)))));}
    private <T>T open(String purpose,byte[] bytes,Class<T> type) throws Exception {
        if(bytes.length>262144)throw denied();var envelope=json.readValue(bytes,Sealed.class);if(!keyId.equals(envelope.keyId()))throw denied();byte[] payload=Base64.getDecoder().decode(envelope.payload()),signature=Base64.getDecoder().decode(envelope.signature());var verifier=Signature.getInstance("Ed25519");verifier.initVerify(publicKey);verifier.update(domain(purpose,payload));if(!verifier.verify(signature))throw denied();return json.readValue(payload,type);
    }
    private static byte[] domain(String purpose,byte[] payload){byte[] prefix=("loadpilot-runtime-"+purpose+"-v1\n").getBytes(StandardCharsets.US_ASCII);byte[] value=Arrays.copyOf(prefix,prefix.length+payload.length);System.arraycopy(payload,0,value,prefix.length,payload.length);return value;}
    private byte[] sign(byte[] payload) throws Exception{var signer=Signature.getInstance("Ed25519");signer.initSign(key);signer.update(payload);return signer.sign();}
    private void install(String profile,byte[] bytes,String expected) throws Exception{
        Files.createDirectories(output);if(Files.isSymbolicLink(output))throw denied();Path root=output.toRealPath();
        synchronized(RuntimeProbeIssuer.class){
            Path lock=root.resolve(".publish.lock");if(Files.isSymbolicLink(lock))throw denied();
            try(var channel=FileChannel.open(lock,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS);var held=channel.lock()){
                Path path=root.resolve(profile+".json");
                if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
                    if(Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw denied();byte[] current;
                    try(var input=Files.newInputStream(path)){current=input.readNBytes(131073);}if(current.length>131072)throw denied();
                    if(Arrays.equals(current,bytes))return;if(expected==null || !SignedRuntimeEvidence.sha(expected) || !sha(current).equals(expected))throw denied();
                }else if(expected!=null)throw denied();
                Path temporary=Files.createTempFile(root,".pending-report-",".tmp");
                try{try(var out=FileChannel.open(temporary,WRITE,LinkOption.NOFOLLOW_LINKS)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);}Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                finally{Files.deleteIfExists(temporary);}
            }
        }
    }
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception invalid){throw denied();}}
    private static IllegalStateException denied(){return new IllegalStateException("Runtime probe issuance requires authenticated complete current evidence and confirmed cleanup.");}
}
