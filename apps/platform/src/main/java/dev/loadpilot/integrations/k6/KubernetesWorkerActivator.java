package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonParser;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import java.util.*;

/** Two-phase activation primitive. Caller must authorize runtime/egress and commit the exact patch intent. */
public final class KubernetesWorkerActivator implements AutoCloseable {
    public record Resources(String filesUid,String networkUid,String jobUid) {}
    public record Prepared(String planHash,String namespace,String workerName,String jobUid,String resourceVersion,
                           String patchJson,String patchHash,String expectedJobJson) {}
    public record Receipt(String jobUid,String resourceVersion) {}
    public static final class Problem extends RuntimeException {
        private final int status;
        public Problem(int status,String message) {super(message);this.status=status;}
        public int status() {return status;}
    }
    private final ApiClient client;private final ObjectMapper json;
    public KubernetesWorkerActivator(ApiClient ownedClient,ObjectMapper json) {client=KubernetesWorkerTransport.bound(ownedClient);this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    public Prepared prepare(K6PlanEnvelope.Prepared plan,Resources identities) {
        if(plan==null || plan.canonicalJson()==null || !K6PlanEnvelope.hash(plan.canonicalJson()).equals(plan.sha256()) || identities==null
                || !uid(identities.filesUid()) || !uid(identities.networkUid()) || !uid(identities.jobUid())) throw new IllegalArgumentException("Exact saved plan and acknowledged resource UIDs are required.");
        V1Job wanted;V1ConfigMap files;V1NetworkPolicy network;
        try {
            var body=json.readTree(plan.canonicalJson());
            if(!"k6-execution-plan-v2".equals(body.path("schemaVersion").asText()) || !Set.of("job-planner-v2","job-planner-v3","job-planner-v4","job-planner-v5","job-planner-v6").contains(body.path("plannerVersion").asText()) || body.path("submissionReady").asBoolean(true)) throw new IllegalArgumentException();
            wanted=JSON.deserialize(body.path("job").toString(),V1Job.class);files=JSON.deserialize(body.path("files").toString(),V1ConfigMap.class);network=JSON.deserialize(body.path("network").toString(),V1NetworkPolicy.class);
            if(wanted.getSpec()==null || !Boolean.TRUE.equals(wanted.getSpec().getSuspend()) || wanted.getMetadata()==null
                    || !body.path("workerName").asText().equals(wanted.getMetadata().getName()) || !body.path("clusterBinding").path("namespace").asText().equals(wanted.getMetadata().getNamespace())) throw new IllegalArgumentException();
        } catch(Exception invalid) {throw new IllegalArgumentException("Saved activation plan cannot be verified.");}
        String namespace=wanted.getMetadata().getNamespace(),name=wanted.getMetadata().getName();
        try {
            var actualFiles=new CoreV1Api(client).readNamespacedConfigMap(name,namespace).execute();
            if(!metadata(files.getMetadata(),actualFiles.getMetadata(),identities.filesUid()) || !Boolean.TRUE.equals(actualFiles.getImmutable()) || !Objects.equals(files.getData(),actualFiles.getData())
                    || (actualFiles.getBinaryData()!=null && !actualFiles.getBinaryData().isEmpty())) throw conflict();
            var actualNetwork=new NetworkingV1Api(client).readNamespacedNetworkPolicy(name,namespace).execute();
            if(!metadata(network.getMetadata(),actualNetwork.getMetadata(),identities.networkUid()) || !Objects.equals(network.getSpec(),actualNetwork.getSpec())) throw conflict();
            var actual=new BatchV1Api(client).readNamespacedJob(name,namespace).execute();
            verifyJob(wanted,actual,identities.jobUid(),true);
            if(actual.getStatus()!=null && ((actual.getStatus().getActive()!=null && actual.getStatus().getActive()>0) || (actual.getStatus().getSucceeded()!=null && actual.getStatus().getSucceeded()>0)
                    || (actual.getStatus().getFailed()!=null && actual.getStatus().getFailed()>0) || (actual.getStatus().getConditions()!=null && actual.getStatus().getConditions().stream().anyMatch(c->"True".equals(c.getStatus()) && Set.of("Complete","Failed").contains(c.getType()))))) throw conflict();
            var spec=json.readTree(JSON.serialize(actual)).path("spec");
            String patch=json.writeValueAsString(List.of(Map.of("op","test","path","/metadata/uid","value",identities.jobUid()),
                    Map.of("op","test","path","/metadata/resourceVersion","value",actual.getMetadata().getResourceVersion()),
                    Map.of("op","test","path","/spec","value",spec),Map.of("op","replace","path","/spec/suspend","value",false)));
            return new Prepared(plan.sha256(),namespace,name,identities.jobUid(),actual.getMetadata().getResourceVersion(),patch,K6PlanEnvelope.hash(patch),JSON.serialize(wanted));
        } catch(Problem known) {throw known;}
        catch(ApiException failed) {throw failure(failed);}
        catch(Exception invalid) {throw new Problem(503,"Worker activation inspection could not be verified.");}
    }
    public Receipt activate(Prepared command,UUID activationOperationId) {
        validateCommand(command,json);
        if(activationOperationId==null) throw new IllegalArgumentException("A durable activation intent is required.");
        try {
            // This pinned SDK selects application/json by default. Preserve its scoped auth/path but use JSON Patch media/body explicitly.
            var sdk=new BatchV1Api(client).patchNamespacedJob(command.workerName(),command.namespace(),new V1Patch(command.patchJson())).buildCall(null);
            var request=sdk.request().newBuilder().header("Content-Type","application/json-patch+json")
                    .method("PATCH",okhttp3.RequestBody.create(command.patchJson(),okhttp3.MediaType.get("application/json-patch+json"))).build();
            var response=client.<V1Job>execute(client.getHttpClient().newCall(request),V1Job.class).getData();
            try {verifyJob(JSON.deserialize(command.expectedJobJson(),V1Job.class),response,command.jobUid(),false);}
            catch(Problem drift) {throw new Problem(503,"Activation response changed the reviewed worker; reconcile its state before continuing.");}
            if(command.resourceVersion().equals(response.getMetadata().getResourceVersion())) throw new Problem(503,"Worker activation revision was not acknowledged.");
            return new Receipt(response.getMetadata().getUid(),response.getMetadata().getResourceVersion());
        } catch(Problem known) {throw known;}
        catch(ApiException failed) {throw failure(failed);}
        catch(Exception invalid) {throw new Problem(503,"Worker activation acknowledgement could not be verified.");}
    }
    public static void validateCommand(Prepared command,ObjectMapper json) {
        try {
            var strict=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            if(command==null || !uid(command.jobUid()) || command.planHash()==null || !command.planHash().matches("[a-f0-9]{64}") || command.resourceVersion()==null || command.resourceVersion().isBlank() || command.resourceVersion().length()>256 || command.patchJson()==null || command.patchJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536
                    || !K6PlanEnvelope.hash(command.patchJson()).equals(command.patchHash())) throw new IllegalArgumentException();
            var patch=strict.readTree(command.patchJson());
            if(!patch.isArray() || patch.size()!=4 || !test(patch.get(0),"/metadata/uid") || !command.jobUid().equals(patch.get(0).path("value").asText())
                    || !test(patch.get(1),"/metadata/resourceVersion") || !command.resourceVersion().equals(patch.get(1).path("value").asText()) || !test(patch.get(2),"/spec")
                    || !patch.get(2).path("value").path("suspend").isBoolean() || !patch.get(2).path("value").path("suspend").asBoolean()
                    || !"replace".equals(patch.get(3).path("op").asText()) || !"/spec/suspend".equals(patch.get(3).path("path").asText())
                    || !patch.get(3).path("value").isBoolean() || patch.get(3).path("value").asBoolean()) throw new IllegalArgumentException();
            for(var step:patch) if(!step.isObject() || !step.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(Set.of("op","path","value"))) throw new IllegalArgumentException();
            var expected=JSON.<V1Job>deserialize(command.expectedJobJson(),V1Job.class);
            if(expected.getMetadata()==null || !Objects.equals(expected.getMetadata().getName(),command.workerName()) || !Objects.equals(expected.getMetadata().getNamespace(),command.namespace()) || expected.getSpec()==null || !Boolean.TRUE.equals(expected.getSpec().getSuspend())) throw new IllegalArgumentException();
            var actual=(ObjectNode)patch.get(2).path("value").deepCopy();normalizeDefaults(actual,command.jobUid(),command.workerName(),strict);
            var wanted=(ObjectNode)strict.readTree(JSON.serialize(expected)).path("spec");normalizeDefaults(wanted,command.jobUid(),command.workerName(),strict);
            if(!actual.equals(wanted)) throw new IllegalArgumentException();
        } catch(Exception invalid) {throw new IllegalArgumentException("Exact conditional worker activation patch is required.");}
    }
    private static boolean test(JsonNode step,String path) {return "test".equals(step.path("op").asText()) && path.equals(step.path("path").asText());}
    private void verifyJob(V1Job expected,V1Job actual,String jobUid,boolean suspended) throws Exception {
        verifySavedJob(expected,actual,jobUid,suspended,json);
    }
    static void verifySavedJob(V1Job expected,V1Job actual,String jobUid,boolean suspended,ObjectMapper json) throws Exception {
        if(actual==null || actual.getSpec()==null || !metadata(expected.getMetadata(),actual.getMetadata(),jobUid) || actual.getMetadata().getResourceVersion()==null || actual.getMetadata().getResourceVersion().isBlank()
                || !Objects.equals(actual.getSpec().getSuspend(),suspended)) throw conflict();
        var wanted=(ObjectNode)json.readTree(JSON.serialize(expected)).path("spec");wanted.put("suspend",suspended);
        normalizeDefaults(wanted,jobUid,expected.getMetadata().getName(),json);
        var observed=(ObjectNode)json.readTree(JSON.serialize(actual)).path("spec");normalizeDefaults(observed,jobUid,expected.getMetadata().getName(),json);
        if(!wanted.equals(observed)) throw conflict();
    }
    private static void normalizeDefaults(ObjectNode spec,String uid,String name,ObjectMapper json) {
        var selector=spec.path("selector");
        if(selector.isObject() && selector.properties().stream().allMatch(e->Set.of("matchLabels","matchExpressions").contains(e.getKey()))
                && (!selector.hasNonNull("matchExpressions") || (selector.path("matchExpressions").isArray() && selector.path("matchExpressions").isEmpty())) && selector.path("matchLabels").isObject() && !selector.path("matchLabels").isEmpty()
                && selector.path("matchLabels").properties().stream().allMatch(e->Set.of("controller-uid","batch.kubernetes.io/controller-uid").contains(e.getKey()) && uid.equals(e.getValue().asText()))) spec.remove("selector");
        removeDefault(spec,"manualSelector",false,json);removeDefault(spec,"completionMode","NonIndexed",json);removeDefault(spec,"managedBy","kubernetes.io/job-controller",json);
        var labels=spec.path("template").path("metadata").path("labels");
        if(labels instanceof ObjectNode values) for(String key:List.of("controller-uid","batch.kubernetes.io/controller-uid","job-name","batch.kubernetes.io/job-name")) {
            String expected=key.endsWith("job-name")?name:uid;if(expected.equals(values.path(key).asText())) values.remove(key);
        }
        var pod=spec.path("template").path("spec");
        if(pod instanceof ObjectNode values) {
            removeDefault(values,"schedulerName","default-scheduler",json);removeDefault(values,"serviceAccount",values.path("serviceAccountName").asText(),json);removeDefault(values,"priority",0,json);removeDefault(values,"preemptionPolicy","PreemptLowerPriority",json);
            for(var container:values.path("containers")) if(container instanceof ObjectNode value) {removeDefault(value,"terminationMessagePath","/dev/termination-log",json);removeDefault(value,"terminationMessagePolicy","File",json);}
        }
    }
    private static void removeDefault(ObjectNode node,String field,Object value,ObjectMapper json) {if(json.valueToTree(value).equals(node.get(field))) node.remove(field);}
    private static boolean metadata(V1ObjectMeta expected,V1ObjectMeta actual,String uid) {
        return expected!=null && actual!=null && Objects.equals(expected.getName(),actual.getName()) && Objects.equals(expected.getNamespace(),actual.getNamespace()) && uid.equals(actual.getUid())
                && actual.getLabels()!=null && expected.getLabels()!=null && actual.getLabels().entrySet().containsAll(expected.getLabels().entrySet()) && actual.getAnnotations()!=null && expected.getAnnotations()!=null && actual.getAnnotations().entrySet().containsAll(expected.getAnnotations().entrySet());
    }
    private static boolean uid(String value) {return value!=null && value.matches("[A-Za-z0-9._:-]{1,128}");}
    private static Problem conflict() {return new Problem(409,"Prepared worker resources changed; reconcile before activation.");}
    private static Problem failure(ApiException failed) {return failed.getCode()==409 || failed.getCode()==422?conflict():new Problem(failed.getCode()==401 || failed.getCode()==403?403:503,"Worker activation outcome could not be confirmed; reconcile before retrying.");}
    @Override public void close() {client.getHttpClient().connectionPool().evictAll();client.getHttpClient().dispatcher().executorService().shutdown();}
}
