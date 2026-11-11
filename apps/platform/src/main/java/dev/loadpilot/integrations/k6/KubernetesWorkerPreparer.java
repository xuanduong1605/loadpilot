package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Creates one reviewed resource per call; never activates a worker or adopts an existing resource.
 * Caller must commit the exact resource intent and authorize its scoped client before I/O.
 * A receipt is not runtime/admission-policy proof; activation requires independent verification.
 */
public final class KubernetesWorkerPreparer implements AutoCloseable {
    public enum Resource { FILES, NETWORK, JOB }
    public record Receipt(Resource resource,String namespace,String name,String uid) {}
    public static final class Problem extends RuntimeException {
        private final int status;
        public Problem(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
    private final ApiClient client;private final ObjectMapper json;
    public KubernetesWorkerPreparer(ApiClient ownedClient,ObjectMapper json){client=KubernetesWorkerTransport.bound(ownedClient);this.json=json;}
    public Receipt create(K6PlanEnvelope.Prepared plan,UUID submitOperationId,Resource resource) {
        if(plan==null || plan.canonicalJson()==null || plan.canonicalJson().getBytes(StandardCharsets.UTF_8).length>524288 || submitOperationId==null || resource==null || !K6PlanEnvelope.hash(plan.canonicalJson()).equals(plan.sha256())) throw new IllegalArgumentException("An exact reviewed resource plan and submit intent are required.");
        V1Job job;V1ConfigMap files;V1NetworkPolicy network;
        try {
            var body=json.readTree(plan.canonicalJson());
            if(!"k6-execution-plan-v2".equals(body.path("schemaVersion").asText()) || !Set.of("job-planner-v2","job-planner-v3","job-planner-v4","job-planner-v5","job-planner-v6").contains(body.path("plannerVersion").asText()) || body.path("submissionReady").asBoolean(true) || !submitOperationId.toString().equals(body.path("submitOperationId").asText())) throw new IllegalArgumentException();
            job=JSON.deserialize(body.path("job").toString(),V1Job.class);files=JSON.deserialize(body.path("files").toString(),V1ConfigMap.class);network=JSON.deserialize(body.path("network").toString(),V1NetworkPolicy.class);
            if(job==null || job.getSpec()==null || !Boolean.TRUE.equals(job.getSpec().getSuspend()) || job.getMetadata()==null || files==null || network==null || !Boolean.TRUE.equals(files.getImmutable())) throw new IllegalArgumentException();
            for(var metadata:List.of(job.getMetadata(),files.getMetadata(),network.getMetadata())) {
                if(metadata==null || !body.path("workerName").asText().equals(metadata.getName()) || !body.path("clusterBinding").path("namespace").asText().equals(metadata.getNamespace()) || metadata.getAnnotations()==null || !submitOperationId.toString().equals(metadata.getAnnotations().get("loadpilot.dev/submit-operation"))) throw new IllegalArgumentException();
            }
        } catch(Exception invalid){throw new IllegalArgumentException("Worker resource plan cannot be verified.");}
        String namespace=job.getMetadata().getNamespace();V1ObjectMeta actual;
        try {
            switch(resource) {
                case FILES -> {var response=new CoreV1Api(client).createNamespacedConfigMap(namespace,files).execute();
                    if(response==null || !Boolean.TRUE.equals(response.getImmutable()) || !files.getData().equals(response.getData())) throw new Problem(503,"Worker files were not acknowledged unchanged; reconcile before continuing.");actual=response.getMetadata();}
                case NETWORK -> {var response=new NetworkingV1Api(client).createNamespacedNetworkPolicy(namespace,network).execute();
                    if(response==null || !KubernetesNetworkPolicy.matches(json,network.getSpec(),response.getSpec())) throw new Problem(503,"Worker network policy was not acknowledged unchanged; reconcile before continuing.");actual=response.getMetadata();}
                case JOB -> {var response=new BatchV1Api(client).createNamespacedJob(namespace,job).execute();
                    if(response==null || response.getSpec()==null || !Boolean.TRUE.equals(response.getSpec().getSuspend())) throw new Problem(503,"Worker Job suspension was not acknowledged; reconcile before continuing.");actual=response.getMetadata();}
                default -> throw new IllegalArgumentException();
            }
            var expected=job.getMetadata();
            if(actual==null || actual.getUid()==null || actual.getUid().isBlank() || !expected.getName().equals(actual.getName()) || !namespace.equals(actual.getNamespace()) || actual.getAnnotations()==null || !actual.getAnnotations().entrySet().containsAll(expected.getAnnotations().entrySet()))
                throw new Problem(503,"Worker resource identity was not acknowledged; reconcile before continuing.");
            return new Receipt(resource,namespace,actual.getName(),actual.getUid()); // Receipt only, never activation/runtime proof.
        } catch(ApiException failed) {
            if(failed.getCode()==409) throw new Problem(409,"Worker resource already exists; reconcile its identity before continuing.");
            if(failed.getCode()==401 || failed.getCode()==403) throw new Problem(403,"Worker preparation permission denied.");
            throw new Problem(503,"Worker resource preparation outcome is uncertain; reconcile before retrying.");
        } catch(Problem known){throw known;}
        catch(RuntimeException invalid){throw new Problem(503,"Worker resource acknowledgement could not be verified.");}
    }
    @Override public void close(){client.getHttpClient().connectionPool().evictAll();client.getHttpClient().dispatcher().executorService().shutdown();}
}
