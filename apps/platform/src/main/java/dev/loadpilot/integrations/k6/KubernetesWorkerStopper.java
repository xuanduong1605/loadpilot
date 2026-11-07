package dev.loadpilot.integrations.k6;

import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.*;
import java.util.UUID;

/** Backend-only deletion primitive. Caller must commit and authorize the exact STOP intent first. */
public final class KubernetesWorkerStopper implements AutoCloseable {
    public record Command(String namespace,String workerName,String jobUid,UUID stopOperationId) {}
    public enum Acknowledgement { ACCEPTED, NOT_FOUND }
    public static final class Problem extends RuntimeException {
        private final int status;
        public Problem(int status,String message) { super(message);this.status=status; }
        public int status() { return status; }
    }
    private final ApiClient client;
    private final BatchV1Api jobs;
    public KubernetesWorkerStopper(ApiClient ownedClient) { client=KubernetesWorkerTransport.bound(ownedClient);jobs=new BatchV1Api(client); }
    public Acknowledgement request(Command command) {
        if(command==null || !name(command.namespace()) || !name(command.workerName()) || command.jobUid()==null || !command.jobUid().matches("[A-Za-z0-9._:-]{1,128}") || command.stopOperationId()==null)
            throw new IllegalArgumentException("An exact namespace, worker UID and durable stop intent are required.");
        var options=new V1DeleteOptions().propagationPolicy("Foreground").preconditions(new V1Preconditions().uid(command.jobUid()));
        try {
            var response=jobs.deleteNamespacedJob(command.workerName(),command.namespace()).body(options).execute();
            if(response==null || (response.getStatus()!=null && !response.getStatus().equals("Success")) || (response.getCode()!=null && (response.getCode()<200 || response.getCode()>=300)))
                throw new Problem(503,"Worker stop acknowledgement could not be verified.");
            return Acknowledgement.ACCEPTED; // Never confirms Pod termination or changes a run state.
        } catch(ApiException failed) {
            if(failed.getCode()==404) return Acknowledgement.NOT_FOUND;
            if(failed.getCode()==409) throw new Problem(409,"Worker UID conflict; retain recovery protection.");
            if(failed.getCode()==401 || failed.getCode()==403) throw new Problem(403,"Worker stop permission denied; retain recovery protection.");
            throw new Problem(503,"Worker stop outcome is uncertain; reconcile before retrying.");
        } catch(RuntimeException invalidResponse) { throw new Problem(503,"Worker stop acknowledgement could not be verified."); }
    }
    private static boolean name(String value) { return value!=null && value.length()<=63 && value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"); }
    @Override public void close() {client.getHttpClient().connectionPool().evictAll();client.getHttpClient().dispatcher().executorService().shutdown();}
}
