package dev.loadpilot;

import com.fasterxml.jackson.databind.*;
import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.k6.*;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RuntimeProbeScopeTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test void prototypeUsesExactReviewedInputsCurrentScopeAndExplicitRuntimeWithoutReservingARun() throws Exception{
        var owner=new K6JobPlanTest();var fixture=owner.fixture(owner.steady(),"export default function(){}\n");var snap=fixture.snapshot();String canonical=json.writeValueAsString(snap.inputs());
        var real=new ExperimentSnapshots.Snapshot(snap.id(),snap.inputs(),K6PlanEnvelope.hash(canonical),snap.createdBy(),snap.createdAt(),true,false);
        var snapshots=mock(ExperimentSnapshots.Store.class);when(snapshots.get(real.id())).thenReturn(real);when(snapshots.canonicalInputs(real.id())).thenReturn(canonical);var runs=mock(RunIntents.class);var clusters=mock(ExecutionClusterClients.class);var source=mock(ExecutionSourcePolicy.class);var destinations=mock(ExecutionDestinationPolicy.class);
        var binding=new ExecutionClusterClients.Binding(real.inputs().target().id(),1,"https://cluster.invalid",List.of("192.0.2.20"),owner.policy.namespace(),List.of("RUNTIME","PROBE_READ"),"a".repeat(64));when(clusters.binding(real.inputs().target().id(),owner.policy.namespace())).thenReturn(binding);when(destinations.pin(real.inputs().target().baseUrl())).thenReturn(owner.destination);
        var api=new RuntimeProbeScopeApi(snapshots,runs,new ExecutionRuntimePolicy(owner.policy),destinations,clusters,source,json,Validation.buildDefaultValidatorFactory().getValidator(),86400);var review=api.review(real.id());assertThat(review.maximumEvidenceValiditySeconds()).isEqualTo(86400);
        assertThat(review.inputsCanonicalJson()).isEqualTo(canonical);assertThat(review.executionAvailable()).isFalse();assertThat(RuntimeProbeCommand.review(review,json)).isEqualTo(real.inputs().target().baseUrl());verify(runs,times(2)).verifySnapshotScope(real.id());verify(source).verify(real.inputs());verify(clusters,never()).open(any());verify(runs,never()).reserve(any(),any());
        var mvc=MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ExperimentSettingsErrors()).build();
        String endpoint="/api/experiments/snapshots/"+real.id()+"/runtime-probe-scope";
        mvc.perform(get(endpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.executionAvailable").value(false))
                .andExpect(jsonPath("$.inputsCanonicalJson").value(canonical));
        var plan=json.readTree(review.sourcePlan().canonicalJson());assertThat(plan.path("snapshotSha256").asText()).isEqualTo(real.sha256());assertThat(plan.path("submissionReady").asBoolean(true)).isFalse();assertThat(plan.path("job").path("spec").path("suspend").asBoolean()).isTrue();

        when(destinations.pin(real.inputs().target().baseUrl())).thenThrow(
                new ExecutionDestinationPolicy.Problem(403,"EXECUTION_DESTINATION_DENIED","This execution origin or resolved address is not allowed."));
        mvc.perform(get(endpoint)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXECUTION_DESTINATION_DENIED"));
        clearInvocations(destinations);

        var incomplete=new ExecutionClusterClients.Binding(binding.targetId(),binding.connectionRevision(),binding.origin(),
                binding.addresses(),binding.namespace(),List.of("RUNTIME"),binding.caSha256());
        when(clusters.binding(binding.targetId(),binding.namespace())).thenReturn(incomplete)
                .thenThrow(new ExecutionClusterClients.Problem(409,"Execution connection revision changed."));
        mvc.perform(get(endpoint)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXECUTION_CLUSTER_SCOPE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The execution cluster grant must include RUNTIME and PROBE_READ to export a probe scope."));
        mvc.perform(get(endpoint)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_CLUSTER_SCOPE_UNAVAILABLE"));
        verifyNoInteractions(destinations);
        doThrow(new ExperimentDrafts.Problem(409,"revoked")).when(runs).verifySnapshotScope(real.id());assertThatThrownBy(()->api.review(real.id())).isInstanceOf(ExperimentDrafts.Problem.class);
        assertThatThrownBy(()->RuntimeProbeCommand.review(new RuntimeProbeScopeApi.Review(review.sourcePlan(),canonical+" ",false),json)).isInstanceOf(IllegalStateException.class);
    }
}
