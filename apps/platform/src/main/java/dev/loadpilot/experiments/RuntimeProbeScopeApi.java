package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import jakarta.validation.Validator;
import java.time.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

/** Operator-only export before a run exists. No reservation, cluster call or execution authority is created. */
@RestController
@RequestMapping("/api/experiments/snapshots")
public class RuntimeProbeScopeApi {
    public record Review(K6PlanEnvelope.Prepared sourcePlan,String inputsCanonicalJson,boolean executionAvailable,int maximumEvidenceValiditySeconds) {
        public Review(K6PlanEnvelope.Prepared sourcePlan,String inputsCanonicalJson,boolean executionAvailable){this(sourcePlan,inputsCanonicalJson,executionAvailable,900);}
    }
    private final ExperimentSnapshots.Store snapshots;private final RunIntents runs;private final ExecutionRuntimePolicy runtime;private final ExecutionDestinationPolicy destinations;
    private final ExecutionClusterClients clusters;private final ExecutionSourcePolicy source;private final K6PlanEnvelope envelopes;
    private final int maximumValiditySeconds;
    public RuntimeProbeScopeApi(ExperimentSnapshots.Store snapshots,RunIntents runs,ExecutionRuntimePolicy runtime,ExecutionDestinationPolicy destinations,ExecutionClusterClients clusters,ExecutionSourcePolicy source,ObjectMapper json,Validator validator,@Value("${platform.execution.runtime-evidence-max-validity-seconds:900}") int maximumValiditySeconds){this.snapshots=snapshots;this.runs=runs;this.runtime=runtime;this.destinations=destinations;this.clusters=clusters;this.source=source;this.envelopes=new K6PlanEnvelope(json,validator);this.maximumValiditySeconds=maximumValiditySeconds;}
    @GetMapping("/{id}/runtime-probe-scope") public Review review(@PathVariable UUID id){
        var snapshot=snapshots.get(id);String canonical=snapshots.canonicalInputs(id);if(!K6PlanEnvelope.hash(canonical).equals(snapshot.sha256()))throw new ExperimentDrafts.Problem(409,"Snapshot changed during review.");
        runs.verifySnapshotScope(id);source.verify(snapshot.inputs());var policy=runtime.current();var binding=clusters.binding(snapshot.inputs().target().id(),policy.namespace());var pin=destinations.pin(snapshot.inputs().target().baseUrl());
        var now=OffsetDateTime.now(ZoneOffset.UTC);UUID attempt=UUID.randomUUID();int maximum=Math.toIntExact(snapshot.inputs().executionBounds().maximumAttemptSeconds(snapshot.inputs().settings()));
        var ticket=new RunDispatch.Ticket(UUID.randomUUID(),id,1,attempt,UUID.randomUUID(),UUID.randomUUID(),"lp-"+attempt,maximum,now.plusSeconds(snapshot.inputs().settings().totalBudgetSeconds()),now);
        var plan=envelopes.prepare(snapshot,ticket,policy,pin,binding,now.toInstant());runs.verifySnapshotScope(id);return new Review(plan,canonical,false,maximumValiditySeconds);
    }
}
