package dev.loadpilot.integrations.k6;

import dev.loadpilot.experiments.*;
import io.kubernetes.client.openapi.models.*;
import java.util.*;

/** Pure interpretation of a trusted collector's complete Job/Pod reads. Does not perform those reads. */
public final class KubernetesWorkerEvidence {
    private KubernetesWorkerEvidence() {}
    public static RunObservations.Observation evaluate(RunDispatch.Ticket ticket,V1Job expected,V1Job observed,V1PodList pods) {
        require(expected!=null && expected.getMetadata()!=null && observed!=null && observed.getMetadata()!=null,"Missing Job identity.");
        var wanted=expected.getMetadata();var actual=observed.getMetadata();
        require(ticket.workerName().equals(wanted.getName()) && ticket.workerName().equals(actual.getName())
                && Objects.equals(wanted.getNamespace(),actual.getNamespace()) && actual.getUid()!=null && !actual.getUid().isBlank()
                && actual.getResourceVersion()!=null && !actual.getResourceVersion().isBlank(),"Job identity differs from the saved plan.");
        Map<String,String> trace=Map.of("loadpilot.dev/run",ticket.runId().toString(),"loadpilot.dev/attempt",ticket.attemptId().toString(),"loadpilot.dev/shard",ticket.shardId().toString());
        require(contains(actual.getLabels(),trace) && contains(wanted.getLabels(),trace),"Job trace identity differs from the run.");
        require(wanted.getAnnotations()!=null && ticket.snapshotId().toString().equals(wanted.getAnnotations().get("loadpilot.dev/snapshot"))
                && ticket.submitOperationId().toString().equals(wanted.getAnnotations().get("loadpilot.dev/submit-operation"))
                && contains(actual.getAnnotations(),wanted.getAnnotations()),"Job input identity differs from the saved plan.");
        boolean allTerminated=podsTerminated(actual.getName(),actual.getNamespace(),actual.getUid(),trace,pods);
        var status=observed.getStatus();boolean complete=false,failed=false;
        if(status!=null && status.getConditions()!=null) for(var condition:status.getConditions()) {
            if("True".equals(condition.getStatus())) {
                complete|="Complete".equals(condition.getType());failed|="Failed".equals(condition.getType());
            }
        }
        require(!(complete && failed),"Job terminal conditions contradict each other.");
        boolean running=pods.getItems().stream().anyMatch(KubernetesWorkerEvidence::generatorRunning);
        var state=complete?RunLifecycle.WorkerState.SUCCEEDED:failed?RunLifecycle.WorkerState.FAILED:running?RunLifecycle.WorkerState.RUNNING:RunLifecycle.WorkerState.PENDING;
        boolean confirmed=(complete || failed) && allTerminated && (status.getActive()==null || status.getActive()==0);
        return new RunObservations.Observation(UUID.randomUUID(),ticket.attemptId(),ticket.submitOperationId(),ticket.revision(),
                ticket.workerName(),actual.getUid(),state,confirmed);
    }
    static RunObservations.Observation afterConfirmedAbsence(RunDispatch.Ticket ticket,V1Job expected,String uid,V1PodList pods) {
        var meta=expected.getMetadata();
        Map<String,String> trace=Map.of("loadpilot.dev/run",ticket.runId().toString(),"loadpilot.dev/attempt",ticket.attemptId().toString(),"loadpilot.dev/shard",ticket.shardId().toString());
        require(ticket.workerName().equals(meta.getName()) && contains(meta.getLabels(),trace) && meta.getAnnotations()!=null
                && ticket.snapshotId().toString().equals(meta.getAnnotations().get("loadpilot.dev/snapshot"))
                && ticket.submitOperationId().toString().equals(meta.getAnnotations().get("loadpilot.dev/submit-operation")),"Saved absence scope differs from the run.");
        boolean ended=podsTerminated(meta.getName(),meta.getNamespace(),uid,trace,pods);
        return new RunObservations.Observation(UUID.randomUUID(),ticket.attemptId(),ticket.submitOperationId(),ticket.revision(),ticket.workerName(),uid,
                ended?RunLifecycle.WorkerState.TERMINATED:RunLifecycle.WorkerState.RUNNING,ended);
    }
    private static boolean podsTerminated(String name,String namespace,String uid,Map<String,String> trace,V1PodList pods) {
        require(pods!=null && pods.getMetadata()!=null && pods.getMetadata().getResourceVersion()!=null && !pods.getMetadata().getResourceVersion().isBlank()
                && (pods.getMetadata().getContinue()==null || pods.getMetadata().getContinue().isEmpty())
                && pods.getItems()!=null && pods.getItems().size()<=16,"A complete bounded Pod listing is required.");
        boolean allTerminated=true;Set<String> identities=new HashSet<>();
        for(var pod:pods.getItems()) {
            require(pod!=null && pod.getMetadata()!=null,"Missing Pod identity.");var meta=pod.getMetadata();
            require(Objects.equals(meta.getNamespace(),namespace) && meta.getUid()!=null && !meta.getUid().isBlank()
                    && identities.add(meta.getUid()) && contains(meta.getLabels(),trace),"Pod identity differs from the run.");
            var owners=meta.getOwnerReferences();
            require(owners!=null && owners.stream().filter(owner->Boolean.TRUE.equals(owner.getController())).count()==1
                    && owners.stream().anyMatch(owner->Boolean.TRUE.equals(owner.getController()) && "Job".equals(owner.getKind())
                    && uid.equals(owner.getUid()) && name.equals(owner.getName())),"Pod is not controlled by the observed Job UID.");
            allTerminated &= terminated(pod);
        }
        return allTerminated;
    }
    private static boolean terminated(V1Pod pod) {
        var status=pod.getStatus();var spec=pod.getSpec();
        if(status==null || spec==null || !("Succeeded".equals(status.getPhase()) || "Failed".equals(status.getPhase()))) return false;
        if(spec.getContainers()==null || spec.getContainers().isEmpty() || !stopped(spec.getContainers().stream().map(V1Container::getName).toList(),status.getContainerStatuses())) return false;
        if(spec.getInitContainers()!=null && !stopped(spec.getInitContainers().stream().map(V1Container::getName).toList(),status.getInitContainerStatuses())) return false;
        return spec.getEphemeralContainers()==null || stopped(spec.getEphemeralContainers().stream().map(V1EphemeralContainer::getName).toList(),status.getEphemeralContainerStatuses());
    }
    private static boolean generatorRunning(V1Pod pod) {
        if(pod.getSpec()==null || pod.getSpec().getContainers()==null || pod.getSpec().getContainers().stream().noneMatch(container->"k6".equals(container.getName()))
                || pod.getStatus()==null || !"Running".equals(pod.getStatus().getPhase()) || pod.getStatus().getContainerStatuses()==null) return false;
        return pod.getStatus().getContainerStatuses().stream().anyMatch(status->"k6".equals(status.getName()) && status.getState()!=null && status.getState().getRunning()!=null
                && status.getState().getTerminated()==null && status.getState().getWaiting()==null);
    }
    private static boolean stopped(List<String> names,List<V1ContainerStatus> statuses) {
        if(names.isEmpty()) return statuses==null || statuses.isEmpty();
        if(statuses==null || statuses.size()!=names.size()) return false;
        var expected=new HashSet<>(names);if(expected.size()!=names.size() || expected.contains(null)) return false;
        for(var status:statuses) if(status==null || !expected.remove(status.getName()) || status.getState()==null || status.getState().getTerminated()==null
                || status.getState().getRunning()!=null || status.getState().getWaiting()!=null) return false;
        return expected.isEmpty();
    }
    private static boolean contains(Map<String,String> actual,Map<String,String> expected) { return actual!=null && actual.entrySet().containsAll(expected.entrySet()); }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
}
