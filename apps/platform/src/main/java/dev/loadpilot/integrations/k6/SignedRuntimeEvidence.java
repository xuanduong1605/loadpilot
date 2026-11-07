package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

/** Verifies an operator-trusted probe issuer, not an operator's policy declaration or a browser assertion. */
public final class SignedRuntimeEvidence {
    public static final String VERSION="runtime-probe-evidence-v2";
    public record Envelope(String keyId,String payload,String signature) {}
    public record NodeProbe(String name,String uid,String imageId,String artifactSha256,
                            Instant controllerBefore,Instant controllerAfter,long workerEpochMillis,
                            int allowedRequests,int baselineBeforeRequests,int baselineAfterRequests,int blockedRequests,int blockedResponses,
                            int requestTimeoutMs,long timeoutElapsedMs,int timeoutCount,
                            int lateGuardExitCode,int expiredGuardExitCode,int validIterations,int rejectedIterations,
                            int podDeadlineSeconds,int terminationGraceSeconds,Instant deadlineStartedAt,Instant deadlineFinishedAt,
                            String deadlineReason,int deadlineExitCode) {}
    public record Report(String version,UUID verificationId,String profileSha256,String stateSha256,
                         Instant issuedAt,Instant validUntil,List<NodeProbe> nodes) {}
    public record Verified(Report report,String sha256) {}
    private final ObjectMapper json;
    private final Path directory;
    private final Map<String,PublicKey> keys;
    private final int maximumValiditySeconds;

    public SignedRuntimeEvidence(String directory,String keysJson,ObjectMapper json) {
        this(directory,keysJson,json,900);
    }
    public SignedRuntimeEvidence(String directory,String keysJson,ObjectMapper json,int maximumValiditySeconds) {
        if(maximumValiditySeconds<1 || maximumValiditySeconds>604800)throw new IllegalArgumentException("Runtime evidence validity must be explicitly bounded to1..604800 seconds.");this.maximumValiditySeconds=maximumValiditySeconds;
        this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        if((directory==null || directory.isBlank()) && (keysJson==null || keysJson.isBlank())){this.directory=null;keys=Map.of();return;}
        try {
            if(directory==null || directory.isBlank() || keysJson==null || keysJson.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException();
            this.directory=Path.of(directory).toAbsolutePath().normalize();
            var values=this.json.readTree(keysJson);if(!values.isObject() || values.isEmpty() || values.size()>8)throw new IllegalArgumentException();
            Map<String,PublicKey> parsed=new HashMap<>();
            for(var entry:values.properties()){
                if(!entry.getKey().matches("[A-Za-z0-9_-]{1,64}") || !entry.getValue().isTextual())throw new IllegalArgumentException();
                var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(entry.getValue().asText())));
                parsed.put(entry.getKey(),key);
            }
            keys=Map.copyOf(parsed);
        }catch(Exception invalid){throw new IllegalArgumentException("Runtime evidence requires a directory and trusted Ed25519 public keys.");}
    }
    public boolean configured(){return directory!=null && !keys.isEmpty();}
    boolean acceptsIssuer(String id,PublicKey key,Path output){return configured() && directory.equals(output.toAbsolutePath().normalize()) && keys.containsKey(id) && Arrays.equals(keys.get(id).getEncoded(),key.getEncoded());}
    public Verified load(K6PlanEnvelope.Prepared plan,Instant now) {
        try {
            if(!configured())throw denied();String profile=profile(plan,json);
            Path root=directory.toRealPath(),file=root.resolve(profile+".json");
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().getParent().equals(root))throw denied();
            byte[] bytes;
            try(var input=Files.newInputStream(file)){bytes=input.readNBytes(131073);}
            return verifyEnvelope(plan,bytes,now);
        }catch(Exception invalid){throw denied();}
    }
    /** Same consumer validation before an operator issuer atomically publishes a report. */
    public Verified verifyEnvelope(K6PlanEnvelope.Prepared plan,byte[] bytes,Instant now) {
        try {
            if(!configured() || bytes==null || bytes.length>131072)throw denied();String profile=profile(plan,json);
            var envelope=json.readValue(bytes,Envelope.class);var key=keys.get(envelope.keyId());if(key==null)throw denied();
            byte[] payload=Base64.getDecoder().decode(envelope.payload()),signature=Base64.getDecoder().decode(envelope.signature());
            if(payload.length>65536 || signature.length!=64)throw denied();
            var verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(payload);if(!verifier.verify(signature))throw denied();
            var report=json.readValue(payload,Report.class);validate(plan,profile,report,now);
            return new Verified(report,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
        }catch(Exception invalid){throw denied();}
    }
    private void validate(K6PlanEnvelope.Prepared plan,String profile,Report r,Instant now) throws Exception {
        var body=json.readTree(plan.canonicalJson());Instant deadline=Instant.parse(body.path("deadlineAt").asText());
        if(!VERSION.equals(r.version()) || r.verificationId()==null || !profile.equals(r.profileSha256()) || !sha(r.stateSha256())
                || r.issuedAt()==null || r.validUntil()==null || r.issuedAt().isAfter(now) || !r.validUntil().isAfter(now)
                || !r.validUntil().isAfter(r.issuedAt()) || r.validUntil().isAfter(r.issuedAt().plusSeconds(maximumValiditySeconds))
                || deadline.isAfter(r.validUntil()) || r.nodes()==null || r.nodes().isEmpty() || r.nodes().size()>32)throw denied();
        var spec=body.path("job").path("spec").path("template").path("spec");
        int timeout=body.path("requiredSourcePolicy").path("requestTimeoutMs").asInt(-1);
        int podDeadline=spec.path("activeDeadlineSeconds").asInt(-1),grace=spec.path("terminationGracePeriodSeconds").asInt(-1);
        String image=body.path("policy").path("imageDigest").asText(),digest=image.substring(image.lastIndexOf('@')+1);
        Set<String> names=new HashSet<>(),uids=new HashSet<>();
        for(var p:r.nodes()){
            if(p==null || !name(p.name()) || !uid(p.uid()) || !names.add(p.name()) || !uids.add(p.uid()) || !sha(p.artifactSha256())
                    || p.imageId()==null || !(p.imageId().equals(digest) || p.imageId().endsWith("@"+digest))
                    || p.controllerBefore()==null || p.controllerAfter()==null || p.controllerBefore().isBefore(r.issuedAt().minusSeconds(300))
                    || p.controllerAfter().isAfter(r.issuedAt()) || p.controllerAfter().isBefore(p.controllerBefore())
                    || Duration.between(p.controllerBefore(),p.controllerAfter()).toMillis()>2000
                    || p.workerEpochMillis()<p.controllerBefore().toEpochMilli()-1000 || p.workerEpochMillis()>p.controllerAfter().toEpochMilli()+1000
                    || p.allowedRequests()<3 || p.baselineBeforeRequests()<3 || p.baselineAfterRequests()<3 || p.blockedRequests()<3 || p.blockedResponses()!=0
                    || timeout<1 || p.requestTimeoutMs()!=timeout || p.timeoutCount()<3 || p.timeoutElapsedMs()<Math.max(1,timeout-250L) || p.timeoutElapsedMs()>timeout+1000L
                    || p.lateGuardExitCode()!=108 || p.expiredGuardExitCode()!=108 || p.validIterations()!=1 || p.rejectedIterations()!=0
                    || podDeadline<1 || grace<0 || p.podDeadlineSeconds()!=podDeadline || p.terminationGraceSeconds()!=grace
                    || p.deadlineStartedAt()==null || p.deadlineFinishedAt()==null || p.deadlineStartedAt().isBefore(r.issuedAt().minusSeconds(300+podDeadline+grace))
                    || p.deadlineFinishedAt().isAfter(r.issuedAt()) || !p.deadlineFinishedAt().isAfter(p.deadlineStartedAt())
                    || Duration.between(p.deadlineStartedAt(),p.deadlineFinishedAt()).toMillis()<(podDeadline*1000L)
                    || Duration.between(p.deadlineStartedAt(),p.deadlineFinishedAt()).toMillis()>(podDeadline+grace)*1000L
                    || !"DeadlineExceeded".equals(p.deadlineReason()) || p.deadlineExitCode()<0 || p.deadlineExitCode()>255)throw denied();
        }
    }
    /** Stable safety profile: omit only generated resource names and per-run trace selector values. */
    public static String profile(K6PlanEnvelope.Prepared plan,ObjectMapper mapper) {
        try {
            if(plan==null || plan.canonicalJson()==null || plan.canonicalJson().getBytes(StandardCharsets.UTF_8).length>524288 || !K6PlanEnvelope.hash(plan.canonicalJson()).equals(plan.sha256()))throw denied();
            var json=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            var body=json.readTree(plan.canonicalJson());
            if(!"job-planner-v6".equals(body.path("plannerVersion").asText()) || !"k6-execution-plan-v2".equals(body.path("schemaVersion").asText())
                    || !ExecutionSourcePolicy.VERSION.equals(body.path("requiredSourcePolicy").path("version").asText())
                    || !K6RuntimeGuard.VERSION.equals(body.path("workerClock").path("version").asText()))throw denied();
            var job=body.path("job").path("spec").deepCopy();
            ((ObjectNode)job.path("template")).remove("metadata");
            for(var volume:job.path("template").path("spec").path("volumes"))if(volume.has("configMap"))((ObjectNode)volume.path("configMap")).put("name","<reviewed-input>");
            var network=body.path("network").path("spec").deepCopy();
            var labels=(ObjectNode)network.path("podSelector").path("matchLabels");
            for(String key:List.of("loadpilot.dev/run","loadpilot.dev/attempt","loadpilot.dev/shard"))if(labels.has(key))labels.put(key,"<trace>");
            var clock=body.path("workerClock").deepCopy();((ObjectNode)clock).remove("submissionStartedAt");
            var values=new TreeMap<String,Object>();
            for(String key:List.of("clusterBinding","policy","destination","requiredSourcePolicy","options","maximumSeconds"))values.put(key,body.path(key));
            values.put("jobSpec",job);values.put("networkSpec",network);values.put("workerClock",clock);values.put("plannerVersion",body.path("plannerVersion"));
            return K6PlanEnvelope.hash(json.writeValueAsString(json.convertValue(values,Object.class)));
        }catch(Exception invalid){throw denied();}
    }
    static boolean sha(String value){return value!=null && value.matches("[a-f0-9]{64}");}
    static boolean name(String value){return value!=null && value.length()<=63 && value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");}
    static boolean uid(String value){return value!=null && value.matches("[A-Za-z0-9._:-]{1,128}");}
    static IllegalStateException denied(){return new IllegalStateException("Runtime probe evidence is unavailable or no longer matches the execution plan.");}
}
