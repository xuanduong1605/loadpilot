package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import okhttp3.Request;

/** Reads only exact owned probe resources and fixed protocol output. A submitted worker cannot supply probe evidence. */
public final class KubernetesProbeCollector {
    public record Summary(String schemaVersion,UUID probeId,String nodeUid,String phase,int attempts,int successes,int responses,int timeouts,int iterations,Double minimumElapsedMs,Double maximumElapsedMs) {}
    public record Receipts(String filesUid,String networkUid,String jobUid,String podUid) {}
    public static final class Capture {
        private final String planSha256,artifactJson;
        private Capture(String planSha256,String artifactJson){this.planSha256=planSha256;this.artifactJson=artifactJson;}
        public String planSha256(){return planSha256;}
        public String artifactJson(){return artifactJson;}
        @Override public String toString(){return "RuntimeProbeCapture[verified-plan="+planSha256+"]";}
    }
    public static final class Absence {
        private final String artifactJson;
        private Absence(String artifactJson){this.artifactJson=artifactJson;}
        public String artifactJson(){return artifactJson;}
    }
    private final ExecutionClusterClients clients;
    private final ObjectMapper json;
    private final Clock clock;
    public KubernetesProbeCollector(ExecutionClusterClients clients,ObjectMapper json,Clock clock){
        this.clients=clients;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.clock=clock;
    }
    public static List<String> paths(JsonNode probe,String podName){
        String ns=probe.path("job").path("metadata").path("namespace").asText(),name=probe.path("job").path("metadata").path("name").asText(),node=probe.path("node").path("name").asText();
        if(!SignedRuntimeEvidence.name(ns) || !SignedRuntimeEvidence.name(name) || !SignedRuntimeEvidence.name(node))throw denied();
        List<String> paths=new ArrayList<>(List.of("/api/v1/namespaces/"+ns+"/configmaps/"+name,"/apis/networking.k8s.io/v1/namespaces/"+ns+"/networkpolicies/"+name,
                "/apis/batch/v1/namespaces/"+ns+"/jobs/"+name,"/api/v1/nodes/"+node,"/api/v1/namespaces/"+ns+"/pods?limit=17"));
        if(podName!=null){if(!SignedRuntimeEvidence.name(podName) || !podName.startsWith(name+"-"))throw denied();String path="/api/v1/namespaces/"+ns+"/pods/"+podName;paths.add(path);paths.add(path+"/log?container=k6&follow=false&previous=false&timestamps=true&sinceSeconds=2&limitBytes=8192");}return List.copyOf(paths);
    }
    public Capture collect(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared prepared){
        try {
            var probe=new RuntimeProbePlan(json).verify(original,prepared);var binding=json.treeToValue(probe.path("clusterBinding"),ExecutionClusterClients.Binding.class);
            String name=probe.path("job").path("metadata").path("name").asText();var scope=new ExecutionClusterClients.Scope(binding.targetId(),binding.connectionRevision(),binding.namespace(),name,null,ExecutionClusterClients.Access.PROBE_READ);
            var paths=paths(probe,null);long began=System.nanoTime();
            var files=get(scope,binding,original,prepared,paths.get(0),null);var network=get(scope,binding,original,prepared,paths.get(1),null);var job=get(scope,binding,original,prepared,paths.get(2),null);var node=get(scope,binding,original,prepared,paths.get(3),null);
            resource(probe.path("files"),files);resource(probe.path("network"),network);resource(probe.path("job"),job);
            if(!files.path("immutable").asBoolean() || !files.path("data").equals(probe.path("files").path("data")) || (files.hasNonNull("binaryData") && !files.path("binaryData").isEmpty()) || !KubernetesNetworkPolicy.matches(probe.path("network").path("spec"),network.path("spec")))throw denied();
            String jobUid=job.path("metadata").path("uid").asText();var expected=JSON.<V1Job>deserialize(probe.path("job").toString(),V1Job.class);var actual=JSON.<V1Job>deserialize(job.toString(),V1Job.class);
            KubernetesWorkerActivator.verifySavedJob(expected,actual,job.path("spec"),jobUid,false,json);
            if(!probe.path("node").path("name").equals(node.path("metadata").path("name")) || !probe.path("node").path("uid").equals(node.path("metadata").path("uid")) || node.path("metadata").hasNonNull("deletionTimestamp"))throw denied();
            int ready=0;for(var condition:node.path("status").path("conditions"))if("Ready".equals(condition.path("type").asText()) && "True".equals(condition.path("status").asText()))ready++;if(ready!=1)throw denied();
            var listing=get(scope,binding,original,prepared,paths.get(4),null);
            if(!listing.path("metadata").isObject() || !listing.path("items").isArray() || listing.path("items").size()>16 || !listing.path("metadata").path("continue").asText("").isEmpty() || listing.path("metadata").path("remainingItemCount").asLong(0)>0)throw denied();
            List<JsonNode> selected=new ArrayList<>();
            for(var pod:listing.path("items")){
                if(!pod.path("metadata").isObject() || !SignedRuntimeEvidence.uid(pod.path("metadata").path("uid").asText()) || !SignedRuntimeEvidence.name(pod.path("metadata").path("name").asText()))throw denied();
                if(relevant(probe,pod,jobUid))selected.add(pod);
            }
            if(selected.size()!=1)throw denied();var pod=selected.getFirst();var status=pod(probe,pod,expected,jobUid);String podName=pod.path("metadata").path("name").asText();
            var phase=RuntimeProbePlan.Phase.valueOf(probe.path("phase").asText());Instant before=null,after=null;Long epoch=null;Summary summary=null;
            Instant podStarted=Instant.parse(pod.path("status").path("startTime").asText());if(podStarted.isBefore(Instant.parse(probe.path("createdAt").asText())) || podStarted.isAfter(clock.instant()))throw denied();
            if(phase==RuntimeProbePlan.Phase.CLOCK){
                if(!"Running".equals(pod.path("status").path("phase").asText()) || !status.path("state").path("running").isObject())throw denied();
                Instant containerStarted=Instant.parse(status.path("state").path("running").path("startedAt").asText());if(containerStarted.isBefore(podStarted) || containerStarted.isAfter(clock.instant()))throw denied();
                before=clock.instant();String text=getText(scope,binding,original,prepared,paths(probe,podName).get(6),podName);after=clock.instant();
                epoch=clockSample(probe,text,before,after);
            }else{
                var terminated=status.path("state").path("terminated");if(!terminated.isObject() || !(pod.path("status").path("phase").asText().equals("Succeeded") || pod.path("status").path("phase").asText().equals("Failed")))throw denied();
                int exit=terminated.path("exitCode").asInt(-1);if(exit<0 || exit>255)throw denied();
                Instant start=Instant.parse(terminated.path("startedAt").asText()),end=Instant.parse(terminated.path("finishedAt").asText());
                long duration=Duration.between(podStarted,end).toMillis();if(start.isBefore(podStarted) || end.isBefore(start) || duration<0 || end.isAfter(clock.instant().plusSeconds(1)) || duration>expected.getSpec().getActiveDeadlineSeconds()*1000L)throw denied();
                boolean failed=conditions(job,"Failed"),complete=conditions(job,"Complete");
                if(phase==RuntimeProbePlan.Phase.DEADLINE){
                    if(!failed || complete || !"DeadlineExceeded".equals(pod.path("status").path("reason").asText()) || !job.path("status").path("conditions").findValuesAsText("reason").contains("DeadlineExceeded")
                            || duration<expected.getSpec().getTemplate().getSpec().getActiveDeadlineSeconds()*1000L)throw denied();
                }else if(phase==RuntimeProbePlan.Phase.LATE_GUARD || phase==RuntimeProbePlan.Phase.EXPIRED_GUARD){
                    if(exit!=108 || !failed || complete || "DeadlineExceeded".equals(pod.path("status").path("reason").asText()))throw denied();
                }else{
                    if(exit!=0 || !complete || failed || !"Succeeded".equals(pod.path("status").path("phase").asText()))throw denied();
                    String message=terminated.path("message").asText();if(message.getBytes(StandardCharsets.UTF_8).length>3072)throw denied();summary=json.readValue(message,Summary.class);summary(probe,summary);
                }
            }
            var finalPod=get(scope,binding,original,prepared,paths(probe,podName).get(5),podName);if(!same(pod,finalPod))throw denied();
            for(int i=0;i<4;i++){var first=switch(i){case 0->files;case 1->network;case 2->job;default->node;};if(!same(first,get(scope,binding,original,prepared,paths.get(i),null)))throw denied();}
            if(System.nanoTime()-began>20_000_000_000L)throw denied();
            var result=new TreeMap<String,Object>();result.put("version","runtime-probe-capture-v1");result.put("planSha256",prepared.sha256());result.put("phase",phase.name());result.put("node",json.convertValue(probe.path("node"),Object.class));
            result.put("receipts",new Receipts(files.path("metadata").path("uid").asText(),network.path("metadata").path("uid").asText(),jobUid,pod.path("metadata").path("uid").asText()));
            String image=expected.getSpec().getTemplate().getSpec().getContainers().getFirst().getImage();result.put("imageId",image.substring(image.lastIndexOf('@')+1));
            var capturedState=json.createObjectNode();
            if(phase==RuntimeProbePlan.Phase.CLOCK)capturedState.putObject("running").put("startedAt",status.path("state").path("running").path("startedAt").asText());
            else {var terminated=status.path("state").path("terminated");capturedState.putObject("terminated").put("startedAt",terminated.path("startedAt").asText()).put("finishedAt",terminated.path("finishedAt").asText()).put("exitCode",terminated.path("exitCode").asInt());}
            result.put("containerState",json.convertValue(capturedState,Object.class));result.put("podPhase",pod.path("status").path("phase").asText());result.put("podReason",phase==RuntimeProbePlan.Phase.DEADLINE?"DeadlineExceeded":"");result.put("summary",summary);
            result.put("controllerBefore",before==null?null:before.toString());result.put("controllerAfter",after==null?null:after.toString());result.put("workerEpochMillis",epoch);
            result.put("podStartedAt",podStarted.toString());
            result.put("collectedAt",clock.instant().toString());
            result.put("observedHashes",Map.of("files",hash(files),"network",hash(network),"job",hash(job),"pod",hash(pod),"node",hash(node)));
            return new Capture(prepared.sha256(),json.writeValueAsString(result));
        }catch(Exception invalid){throw denied();}
    }
    /** Known captured identities plus two404/read-list rounds. No unknown CREATE is resolved from absence alone. */
    public Absence confirmAbsent(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared prepared,RuntimeProbeArchive.Archived known){
        try {
            var probe=new RuntimeProbePlan(json).verify(original,prepared);
            if(known==null || !prepared.sha256().equals(known.reference().planSha256()) || !K6PlanEnvelope.hash(known.artifactJson()).equals(known.reference().artifactSha256()))throw denied();
            var capture=json.readTree(known.artifactJson());if(!prepared.sha256().equals(capture.path("planSha256").asText()) || !"runtime-probe-capture-v1".equals(capture.path("version").asText()))throw denied();
            var receipts=capture.path("receipts");for(String field:List.of("filesUid","networkUid","jobUid","podUid"))if(!receipts.path(field).isTextual() || !SignedRuntimeEvidence.uid(receipts.path(field).asText()))throw denied();
            var binding=json.treeToValue(probe.path("clusterBinding"),ExecutionClusterClients.Binding.class);String name=probe.path("job").path("metadata").path("name").asText();var scope=new ExecutionClusterClients.Scope(binding.targetId(),binding.connectionRevision(),binding.namespace(),name,null,ExecutionClusterClients.Access.PROBE_READ);
            var paths=paths(probe,null);Instant before=clock.instant();long began=System.nanoTime();
            for(int round=0;round<2;round++){
                for(int i=0;i<3;i++)try(var session=clients.openProbeRead(scope,binding,original,prepared,paths.get(i),null);var response=session.client().getHttpClient().newCall(new Request.Builder().url(binding.origin()+paths.get(i)).get().build()).execute()){if(response.code()!=404)throw denied();}
                var listing=get(scope,binding,original,prepared,paths.get(4),null);
                if(!listing.path("metadata").isObject() || !listing.path("items").isArray() || listing.path("items").size()>16 || !listing.path("metadata").path("continue").asText("").isEmpty() || listing.path("metadata").path("remainingItemCount").asLong(0)>0)throw denied();
                for(var pod:listing.path("items"))if(!pod.path("metadata").isObject() || !SignedRuntimeEvidence.uid(pod.path("metadata").path("uid").asText()) || !SignedRuntimeEvidence.name(pod.path("metadata").path("name").asText()) || relevant(probe,pod,receipts.path("jobUid").asText()) || receipts.path("podUid").equals(pod.path("metadata").path("uid")))throw denied();
            }
            if(System.nanoTime()-began>20_000_000_000L)throw denied();
            return new Absence(json.writeValueAsString(Map.of("version","runtime-probe-absence-v1","planSha256",prepared.sha256(),"captureSha256",known.reference().artifactSha256(),"receipts",json.convertValue(receipts,Object.class),"checkedBefore",before.toString(),"checkedAfter",clock.instant().toString())));
        }catch(Exception invalid){throw denied();}
    }
    private static boolean relevant(JsonNode probe,JsonNode pod,String uid){
        boolean owned=false;for(var owner:pod.path("metadata").path("ownerReferences"))if(uid.equals(owner.path("uid").asText()))owned=true;
        var labels=pod.path("metadata").path("labels");var expected=probe.path("job").path("metadata").path("labels");
        boolean traced=probe.path("probeId").equals(labels.path("loadpilot.dev/probe")) && expected.path("loadpilot.dev/probe-phase").equals(labels.path("loadpilot.dev/probe-phase")) && expected.path("loadpilot.dev/probe-node").equals(labels.path("loadpilot.dev/probe-node"));
        return owned || traced || pod.path("metadata").path("name").asText().startsWith(probe.path("job").path("metadata").path("name").asText()+"-");
    }
    private JsonNode get(ExecutionClusterClients.Scope scope,ExecutionClusterClients.Binding binding,K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared probe,String path,String pod) throws Exception{return json.readTree(getText(scope,binding,original,probe,path,pod));}
    private String getText(ExecutionClusterClients.Scope scope,ExecutionClusterClients.Binding binding,K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared probe,String path,String pod) throws Exception {
        try(var session=clients.openProbeRead(scope,binding,original,probe,path,pod);var response=session.client().getHttpClient().newCall(new Request.Builder().url(binding.origin()+path).get().build()).execute()){
            if(response.code()!=200 || response.body()==null)throw denied();return response.body().string();
        }
    }
    private void resource(JsonNode expected,JsonNode actual){
        for(String field:List.of("name","namespace"))if(!expected.path("metadata").path(field).equals(actual.path("metadata").path(field)))throw denied();
        var meta=actual.path("metadata");if(!meta.path("uid").isTextual() || !SignedRuntimeEvidence.uid(meta.path("uid").asText()) || !meta.path("resourceVersion").isTextual() || meta.path("resourceVersion").asText().isBlank() || meta.hasNonNull("deletionTimestamp") || (meta.hasNonNull("ownerReferences") && !meta.path("ownerReferences").isEmpty()))throw denied();
        for(String field:List.of("labels","annotations"))for(var entry:expected.path("metadata").path(field).properties())if(!entry.getValue().equals(meta.path(field).path(entry.getKey())))throw denied();
    }
    private JsonNode pod(JsonNode probe,JsonNode pod,V1Job expected,String uid) throws Exception {
        var meta=pod.path("metadata");String name=meta.path("name").asText();if(!SignedRuntimeEvidence.name(name) || !name.startsWith(expected.getMetadata().getName()+"-") || !meta.path("uid").isTextual() || !SignedRuntimeEvidence.uid(meta.path("uid").asText()) || !meta.path("resourceVersion").isTextual() || meta.path("resourceVersion").asText().isBlank() || !expected.getMetadata().getNamespace().equals(meta.path("namespace").asText()) || meta.hasNonNull("deletionTimestamp"))throw denied();
        for(var entry:expected.getSpec().getTemplate().getMetadata().getLabels().entrySet())if(!entry.getValue().equals(meta.path("labels").path(entry.getKey()).asText()))throw denied();
        for(var entry:expected.getSpec().getTemplate().getMetadata().getAnnotations().entrySet())if(!entry.getValue().equals(meta.path("annotations").path(entry.getKey()).asText()))throw denied();
        if(meta.path("ownerReferences").size()!=1)throw denied();var owner=meta.path("ownerReferences").get(0);if(!owner.path("controller").asBoolean() || !uid.equals(owner.path("uid").asText()) || !"Job".equals(owner.path("kind").asText()) || !expected.getMetadata().getName().equals(owner.path("name").asText()))throw denied();
        if(!probe.path("node").path("name").asText().equals(pod.path("spec").path("nodeName").asText()))throw denied();
        var copy=(ObjectNode)pod.path("spec").deepCopy();copy.remove("nodeName");
        if(copy.path("tolerations").isArray() && !copy.path("tolerations").isEmpty()){
            for(var value:copy.path("tolerations"))if(!Set.of("node.kubernetes.io/not-ready","node.kubernetes.io/unreachable").contains(value.path("key").asText()) || !"Exists".equals(value.path("operator").asText()) || !"NoExecute".equals(value.path("effect").asText()) || value.path("tolerationSeconds").asLong(-1)!=300 || value.size()!=4)throw denied();copy.remove("tolerations");
        }
        var actualSpec=JSON.<V1PodSpec>deserialize(copy.toString(),V1PodSpec.class);if(!copy.equals(json.readTree(JSON.serialize(actualSpec))))throw denied();
        var synthetic=JSON.<V1Job>deserialize(JSON.serialize(expected),V1Job.class);synthetic.getMetadata().uid(uid).resourceVersion("probe-pod-spec");synthetic.getSpec().getTemplate().spec(actualSpec);
        KubernetesWorkerActivator.verifySavedJob(expected,synthetic,uid,false,json);
        if(pod.path("status").path("containerStatuses").size()!=1 || !pod.path("status").path("initContainerStatuses").isEmpty() || !pod.path("status").path("ephemeralContainerStatuses").isEmpty())throw denied();
        var state=pod.path("status").path("containerStatuses").get(0);String image=expected.getSpec().getTemplate().getSpec().getContainers().getFirst().getImage(),digest=image.substring(image.lastIndexOf('@')+1),actualImage=state.path("imageID").asText();
        if(!"k6".equals(state.path("name").asText()) || !state.path("restartCount").isIntegralNumber() || state.path("restartCount").asInt(-1)!=0 || !state.path("state").isObject() || state.path("state").size()!=1 || !(actualImage.equals(digest) || actualImage.endsWith("@"+digest)))throw denied();
        return state;
    }
    private void summary(JsonNode probe,Summary summary){
        var phase=RuntimeProbePlan.Phase.valueOf(probe.path("phase").asText());int timeout=probe.path("requestTimeoutMs").asInt();
        if(!"runtime-probe-message-v1".equals(summary.schemaVersion()) || summary.probeId()==null || !summary.probeId().toString().equals(probe.path("probeId").asText()) || !summary.nodeUid().equals(probe.path("node").path("uid").asText()) || !phase.name().equals(summary.phase()))throw denied();
        if(phase==RuntimeProbePlan.Phase.VALID_GUARD){if(summary.iterations()!=1 || summary.attempts()!=0 || summary.responses()!=0 || summary.successes()!=0 || summary.timeouts()!=0)throw denied();return;}
        if(summary.attempts()!=3 || summary.iterations()!=0 || summary.minimumElapsedMs()==null || summary.maximumElapsedMs()==null || !Double.isFinite(summary.minimumElapsedMs()) || !Double.isFinite(summary.maximumElapsedMs()) || summary.minimumElapsedMs()<0 || summary.maximumElapsedMs()<summary.minimumElapsedMs() || summary.maximumElapsedMs()>timeout+1000D)throw denied();
        switch(phase){
            case BASELINE_BEFORE,BASELINE_AFTER,ALLOWED->{if(summary.successes()!=3 || summary.responses()!=3 || summary.timeouts()!=0)throw denied();}
            case BLOCKED->{if(summary.responses()!=0 || summary.successes()!=0 || summary.timeouts()<0 || summary.timeouts()>3)throw denied();}
            case TIMEOUT->{if(summary.responses()!=0 || summary.successes()!=0 || summary.timeouts()!=3 || summary.minimumElapsedMs()<Math.max(1,timeout-250))throw denied();}
            default->throw denied();
        }
    }
    private Long clockSample(JsonNode probe,String text,Instant before,Instant after){
        if(text.getBytes(StandardCharsets.UTF_8).length>8192 || after.isBefore(before) || Duration.between(before,after).toMillis()>2000)throw denied();String prefix="LP_PROBE_CLOCK:"+probe.path("probeId").asText()+":"+probe.path("node").path("uid").asText()+":";Long last=null;
        for(String line:text.split("\\R")){if(line.isBlank())continue;int space=line.indexOf(' ');if(space<1)throw denied();Instant.parse(line.substring(0,space));String value=line.substring(space+1);if(!value.startsWith(prefix) || !value.substring(prefix.length()).matches("[0-9]{1,16}"))throw denied();long epoch=Long.parseLong(value.substring(prefix.length()));if(last!=null && epoch<last)throw denied();last=epoch;}
        if(last==null || last<before.toEpochMilli()-1000 || last>after.toEpochMilli()+1000)throw denied();return last;
    }
    private boolean same(JsonNode one,JsonNode two){return one.path("metadata").path("uid").equals(two.path("metadata").path("uid")) && one.path("metadata").path("resourceVersion").equals(two.path("metadata").path("resourceVersion")) && one.equals(two);}
    private static boolean conditions(JsonNode job,String type){int matches=0;for(var condition:job.path("status").path("conditions"))if(type.equals(condition.path("type").asText()) && "True".equals(condition.path("status").asText()))matches++;return matches==1;}
    private String hash(JsonNode body) throws Exception{return K6PlanEnvelope.hash(json.writeValueAsString(json.convertValue(body,Object.class)));}
    private static IllegalStateException denied(){return new IllegalStateException("Fixed probe observations could not be verified; retain probe recovery evidence.");}
}
