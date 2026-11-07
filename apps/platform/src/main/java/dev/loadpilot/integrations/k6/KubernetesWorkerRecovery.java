package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.*;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import java.util.*;
import static dev.loadpilot.integrations.k6.KubernetesWorkerPreparer.Resource;

/** Exact resource readback and UID/resourceVersion delete. No create, patch, activation or log access. */
public final class KubernetesWorkerRecovery implements AutoCloseable {
    public record Found(Resource resource,String uid,String resourceVersion,String sha256) {}
    public record Delete(Resource resource,String namespace,String name,String uid,String resourceVersion) {}
    public static class Problem extends RuntimeException {private final int status;public Problem(int status){super("Worker resource recovery could not be verified.");this.status=status;}public int status(){return status;}}
    private final ApiClient client;private final ObjectMapper json;
    public KubernetesWorkerRecovery(ApiClient owned,ObjectMapper json){this.client=KubernetesWorkerTransport.bound(owned);this.json=json;}
    public static String path(Resource resource,String namespace,String name){
        if(resource==null || !dns(namespace) || !dns(name))throw new IllegalArgumentException("Exact recovery identity is required.");
        return switch(resource){case FILES->"/api/v1/namespaces/"+namespace+"/configmaps/"+name;case NETWORK->"/apis/networking.k8s.io/v1/namespaces/"+namespace+"/networkpolicies/"+name;case JOB->"/apis/batch/v1/namespaces/"+namespace+"/jobs/"+name;};
    }
    public static V1DeleteOptions options(Delete command){
        path(command.resource(),command.namespace(),command.name());
        if(command.uid()==null || !command.uid().matches("[A-Za-z0-9._:-]{1,128}") || command.resourceVersion()==null || command.resourceVersion().isBlank() || command.resourceVersion().length()>256)throw new IllegalArgumentException("A verified UID and revision are required for cleanup.");
        return new V1DeleteOptions().preconditions(new V1Preconditions().uid(command.uid()).resourceVersion(command.resourceVersion())).propagationPolicy("Foreground");
    }
    /** Two complete matching reads, or two404s. An uncertain create with no known UID is still unresolved on404. */
    public Found inspect(K6PlanEnvelope.Prepared plan,Resource resource,String expectedUid,boolean activeJobAllowed){
        try{
            if(plan==null || plan.canonicalJson()==null || plan.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>524288 || !K6PlanEnvelope.hash(plan.canonicalJson()).equals(plan.sha256()))throw new Problem(409);
            var body=json.readTree(plan.canonicalJson());String ns=body.path("clusterBinding").path("namespace").asText(),name=body.path("workerName").asText();path(resource,ns,name);
            Object first=read(resource,ns,name),last=read(resource,ns,name);
            if(first==null || last==null){if(first==last)return null;throw new Problem(409);}
            var one=verify(body,resource,first,expectedUid,activeJobAllowed);var two=verify(body,resource,last,expectedUid,activeJobAllowed);
            if(!one.equals(two))throw new Problem(409);return two;
        }catch(Problem known){throw known;}catch(KubernetesWorkerActivator.Problem conflict){throw new Problem(conflict.status());}catch(Exception invalid){throw new Problem(503);}
    }
    private Object read(Resource resource,String ns,String name){
        try{return switch(resource){case FILES->new CoreV1Api(client).readNamespacedConfigMap(name,ns).execute();case NETWORK->new NetworkingV1Api(client).readNamespacedNetworkPolicy(name,ns).execute();case JOB->new BatchV1Api(client).readNamespacedJob(name,ns).execute();};}
        catch(ApiException failed){if(failed.getCode()==404)return null;throw new Problem(failed.getCode()==401 || failed.getCode()==403?403:503);}
    }
    private Found verify(JsonNode plan,Resource resource,Object actual,String expectedUid,boolean activeAllowed) throws Exception {
        String field=switch(resource){case FILES->"files";case NETWORK->"network";case JOB->"job";};var wanted=plan.path(field);var observed=json.readTree(JSON.serialize(actual));var metadata=observed.path("metadata");
        String uid=metadata.path("uid").asText(),rv=metadata.path("resourceVersion").asText();
        if(!uid.matches("[A-Za-z0-9._:-]{1,128}") || rv.isBlank() || rv.length()>256 || (expectedUid!=null && !uid.equals(expectedUid))
                || !metadata.path("name").equals(wanted.path("metadata").path("name")) || !metadata.path("namespace").equals(wanted.path("metadata").path("namespace"))
                || (metadata.hasNonNull("ownerReferences") && !metadata.path("ownerReferences").isEmpty()))throw new Problem(409);
        for(String key:List.of("labels","annotations"))for(var entry:wanted.path("metadata").path(key).properties())if(!entry.getValue().equals(metadata.path(key).path(entry.getKey())))throw new Problem(409);
        if(resource==Resource.FILES){if(!observed.path("immutable").asBoolean() || !wanted.path("data").equals(observed.path("data")) || (observed.hasNonNull("binaryData") && !observed.path("binaryData").isEmpty()))throw new Problem(409);}
        else if(resource==Resource.NETWORK){if(!wanted.path("spec").equals(observed.path("spec")))throw new Problem(409);}
        else{
            boolean suspended=observed.path("spec").path("suspend").asBoolean();if(!suspended && !activeAllowed)throw new Problem(409);
            KubernetesWorkerActivator.verifySavedJob(JSON.deserialize(wanted.toString(),V1Job.class),(V1Job)actual,uid,suspended,json);
        }
        String canonical=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(json.convertValue(observed,Object.class));
        return new Found(resource,uid,rv,K6PlanEnvelope.hash(canonical));
    }
    public String delete(Delete command){
        var options=options(command);
        try{switch(command.resource()){
            case FILES->new CoreV1Api(client).deleteNamespacedConfigMap(command.name(),command.namespace()).body(options).execute();
            case NETWORK->new NetworkingV1Api(client).deleteNamespacedNetworkPolicy(command.name(),command.namespace()).body(options).execute();
            case JOB->new BatchV1Api(client).deleteNamespacedJob(command.name(),command.namespace()).body(options).execute();
        }return "ACKNOWLEDGED";}catch(ApiException failed){return failed.getCode()==404?"ACKNOWLEDGED":failed.getCode()==409?"CONFLICT":failed.getCode()==401 || failed.getCode()==403?"DENIED":"UNCERTAIN";}
        catch(RuntimeException uncertain){return "UNCERTAIN";}
    }
    private static boolean dns(String s){return s!=null && s.length()<=63 && s.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");}
    @Override public void close(){client.getHttpClient().connectionPool().evictAll();client.getHttpClient().dispatcher().executorService().shutdown();}
}
