package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.loadpilot.experiments.RuntimeProbeScopeApi;
import dev.loadpilot.integrations.ConnectionStore;
import java.io.PrintStream;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;

/** Explicit operator command; it never starts Spring/SQL or applies/deletes Kubernetes resources. */
public final class RuntimeProbeCommand {
    public record Scope(RuntimeProbeScopeApi.Review review,UUID probeId,List<RuntimeProbePlan.Node> nodes,RuntimeProbePlan.Canary canary,
                        String archiveDirectory,String reportDirectory,String issuerKeyId,Map<String,String> trustedKeys,String cniNamespace,String cniDaemonset,Instant validUntil,String expectedCurrentSha256) {}
    public record Material(String url,String ca,String token,long revision){@Override public String toString(){return "ProbeConnectionMaterial[redacted]";}}
    private RuntimeProbeCommand(){}
    public static int run(String[] args,PrintStream out,PrintStream error){return run(args,out,error,Clock.systemUTC());}
    public static int run(String[] args,PrintStream out,PrintStream error,Clock clock){
        try{
            if(args.length==1 && args[0].equals("help")){out.println("runtime-probe-tool begin|prepare|capture|publish <scope.json> <private-connection.json> <private-ed25519.pk8> [node-name PHASE]");out.println("Read-only cluster calls and local archival/publication only. prepare emits owned manifests for review; nothing is applied or deleted. Real probes require separate authorized operator apply/cleanup.");return 0;}
            if(args.length<4 || !Set.of("begin","prepare","capture","publish").contains(args[0]) || ((args[0].equals("prepare") || args[0].equals("capture"))?args.length!=6:args.length!=4))throw denied();
            var json=new ObjectMapper().findAndRegisterModules().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
            var scope=json.readValue(read(Path.of(args[1]),2097152),Scope.class);String url=review(scope.review(),json);var original=scope.review().sourcePlan();var body=json.readTree(original.canonicalJson());var frozen=json.treeToValue(body.path("clusterBinding"),ExecutionClusterClients.Binding.class);
            if(scope.probeId()==null || scope.nodes()==null || scope.nodes().isEmpty() || scope.nodes().size()>32 || scope.trustedKeys()==null || !frozen.operations().containsAll(List.of("RUNTIME","PROBE_READ")))throw denied();
            var material=json.readValue(read(Path.of(args[2]),131072),Material.class);
            var connections=new ExecutionClusterClients.MaterialSource(){
                private void check(UUID id){if(!id.equals(frozen.targetId()))throw denied();}
                public ConnectionStore.View view(UUID id){check(id);return new ConnectionStore.View(material.revision(),material.url(),material.ca(),material.token()!=null && !material.token().isBlank(),"","",false,null);}
                public ConnectionStore.KubernetesMaterial kubernetesMaterial(UUID id){check(id);return new ConnectionStore.KubernetesMaterial(material.revision(),material.url(),material.ca(),material.token());}
            };
            String grants=json.writeValueAsString(List.of(Map.of("targetId",frozen.targetId().toString(),"connectionRevision",frozen.connectionRevision(),"origin",frozen.origin(),"addresses",frozen.addresses(),"namespace",frozen.namespace(),"operations",frozen.operations())));
            var clients=new ExecutionClusterClients(grants,json,connections);if(!clients.binding(frozen.targetId(),frozen.namespace()).equals(frozen))throw denied();
            var key=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(read(Path.of(args[3]),8192)));var pub=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(scope.trustedKeys().get(scope.issuerKeyId()))));
            var archive=new RuntimeProbeArchive(Path.of(scope.archiveDirectory()),json);var state=new KubernetesRuntimeState(clients,json,scope.cniNamespace(),scope.cniDaemonset());var consumer=new SignedRuntimeEvidence(scope.reportDirectory(),json.writeValueAsString(scope.trustedKeys()),json,scope.review().maximumEvidenceValiditySeconds());
            var issuer=new RuntimeProbeIssuer(archive,new KubernetesProbeCollector(clients,json,clock),state,consumer,Path.of(scope.reportDirectory()),scope.issuerKeyId(),key,pub,json,clock);
            switch(args[0]){
                case "begin"->out.println(json.writeValueAsString(issuer.begin(original,scope.probeId(),scope.nodes())));
                case "prepare","capture"->{
                    var node=scope.nodes().stream().filter(n->n.name().equals(args[4])).findFirst().orElseThrow();var phase=RuntimeProbePlan.Phase.valueOf(args[5]);
                    if(args[0].equals("prepare")){var prepared=issuer.prepare(original,scope.probeId(),node,scope.canary(),url,phase);var plan=json.readTree(prepared.canonicalJson());out.println(json.writeValueAsString(Map.of("apiVersion","v1","kind","List","items",List.of(plan.path("files"),plan.path("network"),plan.path("job")))));}
                    else out.println(json.writeValueAsString(issuer.capture(original,scope.probeId(),issuer.prepared(original,scope.probeId(),node,phase)).reference()));
                }
                case "publish"->{List<RuntimeProbePlan.Prepared> plans=new ArrayList<>();for(var node:scope.nodes())for(var phase:RuntimeProbePlan.Phase.values())plans.add(issuer.prepared(original,scope.probeId(),node,phase));out.println(json.writeValueAsString(issuer.publish(original,scope.probeId(),plans,scope.validUntil(),scope.expectedCurrentSha256())));}
                default->throw denied();
            }
            return 0;
        }catch(Exception invalid){error.println("Runtime probe command refused: review scope, local credentials, immutable journal and current probe evidence. No probe mutation was dispatched.");return 2;}
    }
    public static String review(RuntimeProbeScopeApi.Review review,ObjectMapper json){
        try{
            if(review==null || review.executionAvailable() || review.maximumEvidenceValiditySeconds()<1 || review.maximumEvidenceValiditySeconds()>604800 || review.inputsCanonicalJson()==null || review.inputsCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>524288)throw denied();
            var source=review.sourcePlan();SignedRuntimeEvidence.profile(source,json);var plan=json.readTree(source.canonicalJson());var inputs=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(review.inputsCanonicalJson());
            if(!K6PlanEnvelope.hash(review.inputsCanonicalJson()).equals(plan.path("snapshotSha256").asText()) || !"experiment-input-v5".equals(inputs.path("schemaVersion").asText()) || !"SINGLE_RUN".equals(inputs.path("mode").asText()) || !inputs.path("target").path("id").equals(plan.path("clusterBinding").path("targetId")))throw denied();
            var script=inputs.path("scenario").path("script");if(!script.path("approved").asBoolean() || !plan.path("scriptSha256").asText().equals(script.path("sha256").asText()) || !K6PlanEnvelope.hash(script.path("source").asText()).equals(script.path("sha256").asText()) || !script.path("source").equals(plan.path("files").path("data").path("scenario.js")))throw denied();return inputs.path("target").path("baseUrl").asText();
        }catch(Exception invalid){throw denied();}
    }
    private static byte[] read(Path path,int maximum) throws Exception{if(Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw denied();try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length==0 || bytes.length>maximum)throw denied();return bytes;}}
    private static IllegalStateException denied(){return new IllegalStateException("Invalid fixed probe operator scope.");}
}
