package dev.loadpilot.integrations.k6;

import dev.loadpilot.experiments.*;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;


import java.util.*;



/** Read-only collector. Caller supplies an exclusive, scoped, TLS-verified client after cluster preflight. */
public final class KubernetesWorkerCollector implements AutoCloseable {
    public static final class Problem extends RuntimeException {
        private final int status;
        public Problem(int status,String message) { super(message);this.status=status; }
        public int status() { return status; }
    }
    private final ApiClient client;
    private final BatchV1Api jobs;
    private final CoreV1Api pods;
    public KubernetesWorkerCollector(ApiClient ownedClient) {
        this.client=KubernetesWorkerTransport.bound(ownedClient);
        jobs=new BatchV1Api(client);pods=new CoreV1Api(client);
    }
    public RunObservations.Observation collect(RunDispatch.Ticket ticket,V1Job expected,String boundJobUid) {
        return inspect(ticket,expected,boundJobUid).observation();
    }
    private record Read(RunObservations.Observation observation,V1PodList pods) {}
    /** Final fixed-field message from one terminated, UID-bound worker; no log fallback or percentile pooling. */
    public K6TerminalSummary.Collected collectFinal(RunDispatch.Ticket ticket,V1Job expected,String boundJobUid,com.fasterxml.jackson.databind.ObjectMapper json) {
        if(boundJobUid==null) throw new Problem(409,"A bound worker UID is required for final results.");
        var read=inspect(ticket,expected,boundJobUid);
        if(!read.observation().allWorkersTerminated() || read.pods().getItems().size()!=1) throw new Problem(409,"One completely terminated worker Pod is required for final results.");
        try {
            var planned=expected.getSpec().getTemplate().getSpec().getContainers();
            if(planned.size()!=1 || !K6TerminalSummary.PATH.equals(planned.getFirst().getTerminationMessagePath()) || !"File".equals(planned.getFirst().getTerminationMessagePolicy()))
                throw new IllegalArgumentException();
            var pod=read.pods().getItems().getFirst();
            if(pod.getSpec().getContainers().size()!=1 || !"k6".equals(pod.getSpec().getContainers().getFirst().getName())
                    || pod.getMetadata().getName()==null || !pod.getMetadata().getName().startsWith(ticket.workerName()+"-")
                    || !Objects.equals(planned.getFirst().getImage(),pod.getSpec().getContainers().getFirst().getImage())
                    || !Objects.equals(planned.getFirst().getCommand(),pod.getSpec().getContainers().getFirst().getCommand())
                    || !Objects.equals(planned.getFirst().getArgs(),pod.getSpec().getContainers().getFirst().getArgs())
                    || !K6TerminalSummary.PATH.equals(pod.getSpec().getContainers().getFirst().getTerminationMessagePath())
                    || !"File".equals(pod.getSpec().getContainers().getFirst().getTerminationMessagePolicy())) throw new IllegalArgumentException();
            var states=pod.getStatus().getContainerStatuses().stream().filter(state->"k6".equals(state.getName())).toList();
            if(states.size()!=1 || !Integer.valueOf(0).equals(states.getFirst().getRestartCount())) throw new IllegalArgumentException();
            var ended=states.getFirst().getState().getTerminated();
            if(ended.getStartedAt()==null || ended.getFinishedAt()==null || ended.getExitCode()==null || ended.getExitCode()<0 || ended.getExitCode()>255) throw new IllegalArgumentException();
            var started=ended.getStartedAt().toInstant();var finished=ended.getFinishedAt().toInstant();
            if(finished.isBefore(started) || finished.isAfter(java.time.Instant.now().plusSeconds(5)) || java.time.Duration.between(started,finished).compareTo(java.time.Duration.ofSeconds(ticket.maximumSeconds()+5L))>0
                    || (read.observation().state()==RunLifecycle.WorkerState.SUCCEEDED && (ended.getExitCode()!=0 || !"Succeeded".equals(pod.getStatus().getPhase())))) throw new IllegalArgumentException();
            var summary=new K6TerminalSummary(json).parse(ended.getMessage(),ticket,expected);
            if(summary.durationMs()!=null && summary.durationMs()>java.time.Duration.between(started,finished).toMillis()+1000) throw new IllegalArgumentException();
            return new K6TerminalSummary.Collected(pod.getMetadata().getUid(),started,finished,ended.getExitCode(),summary);
        } catch(RuntimeException invalid) {throw new Problem(503,"Final worker summary is unavailable or cannot be verified.");}
    }
    public KubernetesWorkerLogs.PodIdentity metricsPod(RunDispatch.Ticket ticket,V1Job expected,String boundJobUid) {
        var read=inspect(ticket,expected,boundJobUid);
        if(boundJobUid==null || read.observation().state()!=RunLifecycle.WorkerState.RUNNING) throw new Problem(409,"No running metric worker is available.");
        if(read.pods().getItems().size()!=1) throw new Problem(503,"Exactly one owned metric Pod is required; multiple Pods cannot be combined as one worker.");
        var identities=new ArrayList<KubernetesWorkerLogs.PodIdentity>();
        for(var pod:read.pods().getItems()) {
            if(pod.getStatus()!=null && Set.of("Succeeded","Failed").contains(pod.getStatus().getPhase())) continue;
            try {identities.add(KubernetesWorkerLogs.identity(ticket,expected,boundJobUid,pod));}
            catch(IllegalArgumentException invalid) {throw new Problem(503,"A metric Pod identity could not be verified.");}
        }
        if(identities.size()!=1) throw new Problem(503,"One stable running metric Pod is required.");
        return identities.getFirst();
    }
    private Read inspect(RunDispatch.Ticket ticket,V1Job expected,String boundJobUid) {
        if(expected==null || expected.getMetadata()==null || !name(expected.getMetadata().getNamespace()) || !ticket.workerName().equals(expected.getMetadata().getName()))
            throw new IllegalArgumentException("Invalid saved worker identity.");
        String namespace=expected.getMetadata().getNamespace();
        try {
            var first=jobs.readNamespacedJob(ticket.workerName(),namespace).execute();
            if(first.getMetadata()==null || first.getMetadata().getUid()==null || (boundJobUid!=null && !boundJobUid.equals(first.getMetadata().getUid())))
                throw new Problem(409,"Worker Job identity changed; retain recovery protection.");
            String uid=first.getMetadata().getUid();
            // Inspect the complete isolated namespace, not only labels that might have drifted off owned Pods.
            var listing=relevantPods(namespace,uid,ticket);
            var last=jobs.readNamespacedJob(ticket.workerName(),namespace).execute();
            if(last.getMetadata()==null || !uid.equals(last.getMetadata().getUid()) || !Objects.equals(first.getMetadata().getResourceVersion(),last.getMetadata().getResourceVersion()))
                throw new Problem(409,"Worker changed during collection; reobserve before updating the run.");
            return new Read(KubernetesWorkerEvidence.evaluate(ticket,expected,last,listing),listing);
        } catch(Problem known) { throw known; }
        catch(ApiException failure) {
            if(failure.getCode()==404) throw new Problem(404,"Worker was not found; absence alone does not confirm termination.");
            if(failure.getCode()==401 || failure.getCode()==403) throw new Problem(403,"Worker inspection permission was denied.");
            throw new Problem(503,"Worker inspection did not complete; retain recovery protection.");
        } catch(RuntimeException invalidEvidence) {
            throw new Problem(503,"Worker evidence could not be verified; retain recovery protection.");
        }
    }
    /** Only the guarded stop observer may use this after confirmed submission and a bound Job UID. */
    public RunObservations.Observation confirmAbsent(RunDispatch.Ticket ticket,V1Job expected,String boundJobUid) {
        if(expected==null || expected.getMetadata()==null || !name(expected.getMetadata().getNamespace()) || !ticket.workerName().equals(expected.getMetadata().getName())
                || boundJobUid==null || !boundJobUid.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("A bound stopped-worker identity is required.");
        String namespace=expected.getMetadata().getNamespace();
        try {
            requireAbsent(ticket.workerName(),namespace);
            var listing=relevantPods(namespace,boundJobUid,ticket);
            requireAbsent(ticket.workerName(),namespace);
            return KubernetesWorkerEvidence.afterConfirmedAbsence(ticket,expected,boundJobUid,listing);
        } catch(Problem known) { throw known; }
        catch(ApiException | RuntimeException failed) { throw new Problem(503,"Stopped worker absence could not be verified; retain recovery protection."); }
    }
    private void requireAbsent(String worker,String namespace) throws ApiException {
        try { jobs.readNamespacedJob(worker,namespace).execute(); }
        catch(ApiException response) { if(response.getCode()==404) return;throw response; }
        throw new Problem(409,"Worker still exists; absence is not confirmed.");
    }
    private V1PodList relevantPods(String namespace,String uid,RunDispatch.Ticket ticket) throws ApiException {
            var listing=pods.listNamespacedPod(namespace).limit(17).execute();
            if(listing.getMetadata()==null || listing.getItems()==null || listing.getItems().size()>16 || (listing.getMetadata().getContinue()!=null && !listing.getMetadata().getContinue().isEmpty()))
                throw new Problem(503,"Worker Pod listing is incomplete or exceeds the collection bound.");
            List<V1Pod> relevant=new ArrayList<>();
            for(var pod:listing.getItems()) {
                if(pod==null || pod.getMetadata()==null) throw new Problem(503,"Worker Pod identity is missing.");
                var meta=pod.getMetadata();
                boolean owned=meta.getOwnerReferences()!=null && meta.getOwnerReferences().stream().anyMatch(owner->owner!=null && uid.equals(owner.getUid()));
                boolean traced=meta.getLabels()!=null && ticket.attemptId().toString().equals(meta.getLabels().get("loadpilot.dev/attempt"));
                boolean named=meta.getName()!=null && meta.getName().startsWith(ticket.workerName()+"-");
                if(owned || traced || named) relevant.add(pod);
            }
            return new V1PodList().metadata(listing.getMetadata()).items(relevant);
    }
    private static boolean name(String value) { return value!=null && value.length()<=63 && value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"); }
    @Override public void close() { client.getHttpClient().connectionPool().evictAll();client.getHttpClient().dispatcher().executorService().shutdown(); }
}
