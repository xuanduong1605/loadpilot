package dev.loadpilot;

import dev.loadpilot.experiments.ExperimentSettingsApi;
import dev.loadpilot.experiments.ExecutionBounds;
import dev.loadpilot.experiments.LoadProfile;
import dev.loadpilot.experiments.SloPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExperimentSettingsTest {
    private final ExperimentSettingsApi api = new ExperimentSettingsApi();
    private final SloPolicy slo = new SloPolicy(SloPolicy.Percentile.P95, 250, 0.01,
            SloPolicy.SuccessDefinition.HTTP_200_399_WITHOUT_TRANSPORT_ERROR, null, null, null, List.of());
    @Test void acceptsInclusiveBudgetAndZeroErrorThreshold() {
        var review = api.validate(new ExperimentSettingsApi.Settings(1, 10, 60, 10, 250, 0.0, 2, 2, 120));
        assertThat(review.valid()).isTrue();
        assertThat(review.loadUnit()).isEqualTo("iterations/s");
    }
    @Test void rejectsInvertedBoundsEmptyWindowAndInsufficientBudgetsTogether() {
        var review = api.validate(new ExperimentSettingsApi.Settings(20, 10, 60, 60, 250, 0.01, 3, 2, 179));
        assertThat(review.valid()).isFalse();
        assertThat(review.errors()).hasSize(4);
    }
    @Test void includesRetryStartupAndShutdownAtExactBudgetBoundary() {
        var settings = new ExperimentSettingsApi.Settings(1, 10, 60, 10, 250, 0.01, 2, 4, 268);
        var bounds = new ExecutionBounds(1, 1, 2, 1, 250, 128, 1000, 5, 2, 2);
        var result = api.validateEffective(new ExperimentSettingsApi.EffectiveInput(settings, bounds, new LoadProfile(LoadProfile.Mode.ADAPTIVE_LOAD, LoadProfile.Shape.STEADY, null, List.of(), 50), slo));
        assertThat(result.valid()).isTrue();
        assertThat(result.maximumAttemptSeconds()).isEqualTo(67);
        assertThat(result.reservedAttemptsPerLoad()).isEqualTo(4);
    }
    @Test void rejectsVuInversionAndInsufficientRetryOrTimeAllowance() {
        var settings = new ExperimentSettingsApi.Settings(1, 10, 60, 10, 250, 0.01, 2, 3, 267);
        var bounds = new ExecutionBounds(1, 4, 2, 1, 250, 128, 1000, 5, 2, 2);
        var result = api.validateEffective(new ExperimentSettingsApi.EffectiveInput(settings, bounds, new LoadProfile(LoadProfile.Mode.ADAPTIVE_LOAD, LoadProfile.Shape.STEADY, null, List.of(), 50), slo));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(3);
    }
}
