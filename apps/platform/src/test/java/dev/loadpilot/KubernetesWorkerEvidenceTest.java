package dev.loadpilot;

import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.k6.KubernetesWorkerEvidence;
import io.kubernetes.client.openapi.models.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KubernetesWorkerEvidenceTest {
    final UUID attempt=UUID.randomUUID();
    final RunDispatch.Ticket ticket=new RunDispatch.Ticket(UUID.randomUUID(),UUID.randomUUID(),2,attempt,UUID.randomUUID(),UUID.randomUUID(),"lp-"+attempt,25,OffsetDateTime.now().plusMinutes(1));
    Map<String,String> labels() { return Map.of("loadpilot.dev/run",ticket.runId().toString(),"loadpilot.dev/attempt",attempt.toString(),"loadpilot.dev/shard",ticket.shardId().toString()); }
    V1Job job(boolean complete) { return new V1Job().metadata(new V1ObjectMeta().name(ticket.workerName()).namespace("runners").uid("job-uid").resourceVersion("123").labels(labels()).annotations(Map.of("loadpilot.dev/snapshot",ticket.snapshotId().toString(),"loadpilot.dev/submit-operation",ticket.submitOperationId().toString())))
            .status(new V1JobStatus().conditions(complete?List.of(new V1JobCondition().type("Complete").status("True")):List.of())); }
    V1Pod pod(String phase,boolean stopped) { return new V1Pod().metadata(new V1ObjectMeta().namespace("runners").uid("pod-uid").labels(labels())
            .ownerReferences(List.of(new V1OwnerReference().controller(true).kind("Job").name(ticket.workerName()).uid("job-uid"))))
            .spec(new V1PodSpec().containers(List.of(new V1Container().name("k6"))))
            .status(new V1PodStatus().phase(phase).containerStatuses(List.of(new V1ContainerStatus().name("k6").state(stopped?new V1ContainerState().terminated(new V1ContainerStateTerminated().exitCode(0)):new V1ContainerState().running(new V1ContainerStateRunning()))))); }
    V1PodList pods(V1Pod... values) { return new V1PodList().metadata(new V1ListMeta().resourceVersion("123")).items(List.of(values)); }
    @Test void terminalJobCannotHideRunningOrMissingContainerEvidence() {
        var expected=job(false);var actual=job(true);var worker=pod("Running",false);
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,expected,actual,pods(worker)).allWorkersTerminated()).isFalse();
        worker.getStatus().phase("Succeeded").containerStatuses(List.of());
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,expected,actual,pods(worker)).allWorkersTerminated()).isFalse();
        var proof=KubernetesWorkerEvidence.evaluate(ticket,expected,actual,pods(pod("Succeeded",true)));
        assertThat(proof.state()).isEqualTo(RunLifecycle.WorkerState.SUCCEEDED);assertThat(proof.allWorkersTerminated()).isTrue();assertThat(proof.workerUid()).isEqualTo("job-uid");
    }
    @Test void activeInitContainerPreventsTerminationAndEmptyListAloneDoesNotProveIt() {
        var worker=pod("Succeeded",true);worker.getSpec().initContainers(List.of(new V1Container().name("sidecar")));
        worker.getStatus().initContainerStatuses(List.of(new V1ContainerStatus().name("sidecar").state(new V1ContainerState().running(new V1ContainerStateRunning()))));
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,job(false),job(true),pods(worker)).allWorkersTerminated()).isFalse();
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,job(false),job(false),pods()).allWorkersTerminated()).isFalse();
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,job(false),job(true),pods()).allWorkersTerminated()).isTrue();
        var active=job(true);active.getStatus().active(1);
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,job(false),active,pods()).allWorkersTerminated()).isFalse();
    }
    @Test void rejectsPartialListingsWrongOwnerAndReplacedJob() {
        var partial=pods();partial.getMetadata().setContinue("next");
        assertThatThrownBy(()->KubernetesWorkerEvidence.evaluate(ticket,job(false),job(true),partial)).hasMessage("A complete bounded Pod listing is required.");
        var worker=pod("Succeeded",true);worker.getMetadata().getOwnerReferences().getFirst().uid("old-job");
        assertThatThrownBy(()->KubernetesWorkerEvidence.evaluate(ticket,job(false),job(true),pods(worker))).hasMessage("Pod is not controlled by the observed Job UID.");
        var replaced=job(true);replaced.getMetadata().name("other-job");
        assertThatThrownBy(()->KubernetesWorkerEvidence.evaluate(ticket,job(false),replaced,pods())).hasMessage("Job identity differs from the saved plan.");
    }
    @Test void failedJobIsExecutionFailureAndContradictoryConditionsAreRejected() {
        var failed=job(false);failed.getStatus().conditions(List.of(new V1JobCondition().type("Failed").status("True")));
        var proof=KubernetesWorkerEvidence.evaluate(ticket,job(false),failed,pods(pod("Failed",true)));
        assertThat(proof.state()).isEqualTo(RunLifecycle.WorkerState.FAILED);assertThat(proof.allWorkersTerminated()).isTrue();
        failed.getStatus().conditions(List.of(new V1JobCondition().type("Failed").status("True"),new V1JobCondition().type("Complete").status("True")));
        assertThatThrownBy(()->KubernetesWorkerEvidence.evaluate(ticket,job(false),failed,pods())).hasMessage("Job terminal conditions contradict each other.");
    }
    @Test void allPodsMustTerminateAndDuplicatePodIdentityIsInvalid() {
        var finished=pod("Succeeded",true);var live=pod("Running",false);live.getMetadata().uid("second-pod");
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,job(false),job(true),pods(finished,live)).allWorkersTerminated()).isFalse();
        assertThatThrownBy(()->KubernetesWorkerEvidence.evaluate(ticket,job(false),job(true),pods(finished,finished))).hasMessage("Pod identity differs from the run.");
    }
    @Test void jobCreationAndActiveCountAloneDoNotProveTheGeneratorIsRunning() {
        var observed=job(false);observed.spec(new V1JobSpec().suspend(true)).getStatus().active(1);
        var pending=KubernetesWorkerEvidence.evaluate(ticket,job(false),observed,pods());
        assertThat(pending.state()).isEqualTo(RunLifecycle.WorkerState.PENDING);assertThat(pending.allWorkersTerminated()).isFalse();
        observed.getSpec().suspend(false);
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,job(false),observed,pods(pod("Pending",false))).state()).isEqualTo(RunLifecycle.WorkerState.PENDING);
        assertThat(KubernetesWorkerEvidence.evaluate(ticket,job(false),observed,pods(pod("Running",false))).state()).isEqualTo(RunLifecycle.WorkerState.RUNNING);
    }
}
