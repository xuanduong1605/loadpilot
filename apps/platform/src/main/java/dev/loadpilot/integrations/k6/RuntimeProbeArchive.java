package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Local operator-owned evidence journal. Durability/identity only; it never turns supplied artifacts into proof. */
public final class RuntimeProbeArchive {
    public record Reference(String planSha256,String artifactSha256) {}
    public record Archived(Reference reference,String artifactJson) {}
    private final Path root;
    private final RuntimeProbePlan plans;
    private final ObjectMapper json;
    public RuntimeProbeArchive(Path directory,ObjectMapper json){
        try{Files.createDirectories(directory);if(Files.isSymbolicLink(directory))throw denied();root=directory.toRealPath();plans=new RuntimeProbePlan(json);this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);}
        catch(Exception invalid){throw denied();}
    }
    /** Save exact intent bytes before an external operator applies these manifests. No receipt means outcome unknown. */
    public void prepare(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared plan){
        plans.verify(original,plan);writeOnce(plan.sha256()+".plan.json",bytes(plan.canonicalJson()));
    }
    public void requirePrepared(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared plan){
        try{plans.verify(original,plan);if(!Arrays.equals(readBounded(plan.sha256()+".plan.json",65536),bytes(plan.canonicalJson())))throw denied();}
        catch(Exception invalid){throw denied();}
    }
    /** A receipt is recorded only after the collector has returned its non-forgeable verified capture. */
    public Archived capture(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared plan,KubernetesProbeCollector.Capture capture){
        try{
            requirePrepared(original,plan);if(capture==null || !plan.sha256().equals(capture.planSha256()))throw denied();
            var reference=store(original,plan,bytes(capture.artifactJson()));writeOnce(plan.sha256()+".capture.json",json.writeValueAsBytes(reference));return existing(original,plan).orElseThrow();
        }catch(Exception invalid){throw denied();}
    }
    public Optional<Archived> existing(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared plan){
        try{
            requirePrepared(original,plan);Path path=root.resolve(plan.sha256()+".capture.json");if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
            var reference=json.readValue(readBounded(plan.sha256()+".capture.json",1024),Reference.class);byte[] artifact=read(original,plan,reference);
            var body=json.readTree(artifact);if(!plan.sha256().equals(body.path("planSha256").asText()) || !"runtime-probe-capture-v1".equals(body.path("version").asText()))throw denied();return Optional.of(new Archived(reference,new String(artifact,java.nio.charset.StandardCharsets.UTF_8)));
        }catch(Exception invalid){throw denied();}
    }
    /** Fixed checkpoint names only. Issuer signatures, not this storage API, authenticate checkpoint contents. */
    public void checkpoint(String kind,String id,byte[] value){
        checkpointName(kind,id);if(value==null || value.length==0 || value.length>262144)throw denied();writeOnce(checkpointName(kind,id),value.clone());
    }
    public Optional<byte[]> checkpoint(String kind,String id){
        try{String name=checkpointName(kind,id);if(!Files.exists(root.resolve(name),LinkOption.NOFOLLOW_LINKS))return Optional.empty();return Optional.of(readBounded(name,262144));}catch(Exception invalid){throw denied();}
    }
    private static String checkpointName(String kind,String id){
        if(!Set.of("baseline","plan","seal","report").contains(kind) || id==null || !(id.matches("[a-f0-9]{64}") || id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))throw denied();return kind+"-"+id+".json";
    }
    /** Caller must validate/sanitize captured API/probe data before archiving. No raw log/error/credential API exists. */
    public Reference store(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared plan,byte[] verifiedArtifact){
        if(verifiedArtifact==null || verifiedArtifact.length==0 || verifiedArtifact.length>262144)throw denied();
        prepare(original,plan);byte[] copy=verifiedArtifact.clone();String hash=hash(copy);writeOnce(hash+".artifact",copy);return new Reference(plan.sha256(),hash);
    }
    public byte[] read(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared plan,Reference reference){
        try{
            plans.verify(original,plan);if(reference==null || !plan.sha256().equals(reference.planSha256()) || !SignedRuntimeEvidence.sha(reference.artifactSha256()))throw denied();
            if(!Arrays.equals(readBounded(plan.sha256()+".plan.json",65536),bytes(plan.canonicalJson())))throw denied();
            byte[] value=readBounded(reference.artifactSha256()+".artifact",262144);if(!hash(value).equals(reference.artifactSha256()))throw denied();return value;
        }catch(Exception invalid){throw denied();}
    }
    private byte[] readBounded(String name,int maximum) throws Exception {
        Path path=root.resolve(name);if(Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().getParent().equals(root))throw denied();
        try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum)throw denied();return bytes;}
    }
    // A JVM lock avoids overlapping FileLock exceptions; the filesystem lock also fences independent issuer processes.
    private void writeOnce(String name,byte[] bytes){
        synchronized(RuntimeProbeArchive.class){
            Path temporary=null;
            try{
                Path lock=root.resolve(".archive.lock");if(Files.isSymbolicLink(lock))throw denied();
                try(var channel=FileChannel.open(lock,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS);var held=channel.lock()){
                    Path destination=root.resolve(name);
                    if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){if(!Arrays.equals(readBounded(name,262144),bytes))throw denied();return;}
                    temporary=Files.createTempFile(root,".pending-",".tmp");
                    try(var output=FileChannel.open(temporary,WRITE,LinkOption.NOFOLLOW_LINKS)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}
                    Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);temporary=null;
                    // Atomic rename is required. Unsupported filesystems fail rather than fall back to a partial write.
                }
            }catch(Exception invalid){throw denied();}
            finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){/* Original journal remains unchanged. */}}
        }
    }
    private static byte[] bytes(String value){return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception invalid){throw denied();}}
    private static IllegalStateException denied(){return new IllegalStateException("Probe archive could not preserve or verify immutable evidence.");}
}
