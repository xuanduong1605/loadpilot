package dev.loadpilot;

import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunMetricsTest {
    final RunIntents runs=mock(RunIntents.class);
    final ExperimentSnapshots.Store snapshots=mock(ExperimentSnapshots.Store.class);
    final SystemInspector inspector=mock(SystemInspector.class);
    final WorkerHttpMetrics http=mock(WorkerHttpMetrics.class);
    final RunMetrics metrics=new RunMetrics(runs,snapshots,inspector,http);
    final K6JobPlanTest owner=new K6JobPlanTest();
    ExperimentSnapshots.Snapshot snapshot;
    RunIntents.Run setup(RunLifecycle.Status status) throws Exception {
        snapshot=owner.fixture(owner.steady(),"export default function() {}\n").snapshot();
        var run=new RunIntents.Run(UUID.randomUUID(),snapshot.id(),snapshot.inputs().target().id(),UUID.randomUUID(),status,2,OffsetDateTime.now().plusMinutes(5),UUID.randomUUID(),UUID.randomUUID(),"lp-fixture",25,List.of(),"UNEVALUATED","worker-uid","RUNNING");
        when(runs.get(run.id())).thenReturn(run);when(snapshots.get(snapshot.id())).thenReturn(snapshot);return run;
    }
    Inspection.Pod pod(String name,Instant time,String cpu,String memory) {
        return new Inspection.Pod(name,"Running",true,0,null,null,null,null,new Inspection.Usage("READY",time,"30s",new BigDecimal(cpu),new BigDecimal(memory)));
    }
    Inspection inspection(List<Inspection.Pod> pods,long revision) {
        var check=new Inspection.Check("READY","fixture",Instant.now(),"Kubernetes PodMetrics",200);
        return new Inspection(snapshot.inputs().target().id(),revision,3,Instant.now(),null,check,check,null,new Inspection.Workload("workload-uid",1L,1L,2,2,2,2),pods);
    }
    @Test void currentResourcesKeepUnitsScopeAndSampleTimesWithoutInventingHttpOrSlo() throws Exception {
        var run=setup(RunLifecycle.Status.RUNNING);var now=Instant.now();
        when(http.read(run)).thenReturn(dev.loadpilot.integrations.k6.K6LiveHttpMetrics.unavailable("UNAVAILABLE","fixture",now));
        when(inspector.resources(run.targetId())).thenReturn(inspection(List.of(pod("api-1",now.minusSeconds(2),"50","67108864"),pod("api-2",now.minusSeconds(1),"25.5","33554432")),1));
        var view=metrics.read(run.id());
        assertThat(view.resources().state()).isEqualTo("READY");assertThat(view.resources().cpuMillicores()).isEqualByComparingTo("75.5");assertThat(view.resources().memoryBytes()).isEqualByComparingTo("100663296");
        assertThat(view.resources().selectedPods()).isEqualTo(2);assertThat(view.resources().readyPods()).isEqualTo(2);assertThat(view.resources().oldestSampleAt()).isEqualTo(now.minusSeconds(2));
        assertThat(view.resources().source()).isEqualTo("Kubernetes PodMetrics");assertThat(view.resources().scope()).isEqualTo("CURRENT_TARGET_SELECTED_CONTAINER");assertThat(view.resources().connectionRevision()).isEqualTo(3);
        assertThat(view.requestedLoadUnit()).isEqualTo("iterations/s");assertThat(view.requestedMeasurementRate()).isEqualTo(3);assertThat(view.pollAfterSeconds()).isEqualTo(5);
        assertThat(view.http().state()).isEqualTo("UNAVAILABLE");assertThat(view.http().requestsPerSecond()).isNull();assertThat(view.http().p95LatencyMs()).isNull();assertThat(view.http().errorRatio()).isNull();assertThat(view.verdict()).isEqualTo("UNEVALUATED");
    }
    @Test void staleMissingDuplicateAndChangedScopeNeverBecomeZeroOrPartialAggregates() throws Exception {
        var run=setup(RunLifecycle.Status.RUNNING);var now=Instant.now();
        for(var pods:List.of(List.of(pod("api-1",now,"50","100"),pod("api-2",now.minusSeconds(121),"25","100")),List.<Inspection.Pod>of(),List.of(pod("same",now,"50","100"),pod("same",now,"25","100")))) {
            when(inspector.resources(run.targetId())).thenReturn(inspection(pods,1));var view=metrics.read(run.id());
            assertThat(view.resources().state()).isEqualTo("INCOMPLETE");assertThat(view.resources().cpuMillicores()).isNull();assertThat(view.resources().memoryBytes()).isNull();
        }
        when(inspector.resources(run.targetId())).thenReturn(inspection(List.of(pod("api",now,"50","100")),2));
        assertThat(metrics.read(run.id()).resources().state()).isEqualTo("SCOPE_CHANGED");assertThat(metrics.read(run.id()).resources().pods()).isEmpty();
    }
    @Test void waitingAndEndedRunsDoNotPollAndBusyMetricsDoNotAlterRunState() throws Exception {
        var waiting=setup(RunLifecycle.Status.QUEUED);assertThat(metrics.read(waiting.id()).resources().state()).isEqualTo("WAITING");verifyNoInteractions(inspector);
        var ended=setup(RunLifecycle.Status.CANCELLED);assertThat(metrics.read(ended.id()).resources().state()).isEqualTo("RUN_ENDED");assertThat(metrics.read(ended.id()).pollAfterSeconds()).isZero();verifyNoInteractions(inspector);
        var active=setup(RunLifecycle.Status.RUNNING);when(inspector.resources(active.targetId())).thenThrow(new ConnectionProblem(429,"INSPECTION_BUSY","private upstream detail"));
        var view=metrics.read(active.id());assertThat(view.status()).isEqualTo(RunLifecycle.Status.RUNNING);assertThat(view.resources().state()).isEqualTo("INSPECTION_BUSY");assertThat(view.resources().cpuMillicores()).isNull();assertThat(view.resources().message()).doesNotContain("private upstream");
    }
}
