package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Destination gate around plan preparation. This does not certify the runtime or submit a worker. */
@Service
public class ExecutionPreflight {
    public record Candidate(K6JobPlan.PinnedTarget destination,K6PlanEnvelope.Prepared plan) {}
    private final RunIntents runs;
    private final ExperimentSnapshots.Store snapshots;
    private final ExecutionPlans plans;
    private final ExecutionDestinationPolicy destinations;
    private final ExecutionRuntimePolicy runtime;
    private final ExecutionClusterClients clusters;
    private final ExecutionSourcePolicy source;
    public ExecutionPreflight(RunIntents runs,ExperimentSnapshots.Store snapshots,ExecutionPlans plans,ExecutionDestinationPolicy destinations,ExecutionRuntimePolicy runtime,ExecutionClusterClients clusters,ExecutionSourcePolicy source) {
        this.runs=runs;this.snapshots=snapshots;this.plans=plans;this.destinations=destinations;this.runtime=runtime;this.clusters=clusters;this.source=source;
    }
    private String url(UUID runId) { return snapshots.get(runs.get(runId).snapshotId()).inputs().target().baseUrl(); }
    public Candidate prepare(UUID runId) {
        var runtimePolicy=runtime.current();
        source.verify(snapshots.get(runs.get(runId).snapshotId()).inputs());
        var binding=clusters.binding(runs.get(runId).targetId(),runtimePolicy.namespace());
        var pin=destinations.pin(url(runId));
        return new Candidate(pin,plans.preview(runId,runtimePolicy,pin,binding));
    }
    public ExecutionPlans.Saved persist(UUID runId,String expectedHash,K6JobPlan.PinnedTarget pin,String actor) {
        var runtimePolicy=runtime.current();
        source.verify(snapshots.get(runs.get(runId).snapshotId()).inputs());
        var binding=clusters.binding(runs.get(runId).targetId(),runtimePolicy.namespace());
        destinations.verifyPin(url(runId),pin); // DNS runs outside plan-store transactions.
        return plans.persist(runId,expectedHash,runtimePolicy,pin,binding,actor);
    }
    public ExecutionPlans.Saved verifySaved(UUID runId,String expectedHash,K6JobPlan.PinnedTarget pin) {
        var runtimePolicy=runtime.current();
        source.verify(snapshots.get(runs.get(runId).snapshotId()).inputs());
        var binding=clusters.binding(runs.get(runId).targetId(),runtimePolicy.namespace());
        destinations.verifyPin(url(runId),pin);
        return plans.verifyCurrent(runId,expectedHash,runtimePolicy,pin,binding);
    }
    public ExecutionPlans.Saved verifyPrepared(UUID runId,String expectedHash,K6JobPlan.PinnedTarget pin) {
        var runtimePolicy=runtime.current();
        source.verify(snapshots.get(runs.get(runId).snapshotId()).inputs());
        var binding=clusters.binding(runs.get(runId).targetId(),runtimePolicy.namespace());
        destinations.verifyPin(url(runId),pin);
        return plans.verifyPrepared(runId,expectedHash,runtimePolicy,pin,binding);
    }
}
