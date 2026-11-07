package dev.loadpilot;

import dev.loadpilot.experiments.ExperimentSettingsApi;
import dev.loadpilot.experiments.SloPolicy;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SloPolicyTest {
    private final ExperimentSettingsApi.Settings settings = new ExperimentSettingsApi.Settings(1, 10, 60, 10, 250, 0.01, 1, 1, 70);
    private SloPolicy policy(SloPolicy.Percentile percentile, SloPolicy.ThroughputKind kind, Double rate, SloPolicy.ThroughputOperator operator) {
        return new SloPolicy(percentile, 250, 0.01, SloPolicy.SuccessDefinition.HTTP_200_399_WITHOUT_TRANSPORT_ERROR, kind, rate, operator, java.util.List.of());
    }
    @Test void acceptsEachPercentileAndExplicitOptionalHttpThroughput() {
        for (var percentile : SloPolicy.Percentile.values()) {
            assertThat(policy(percentile, null, null, null).errors(settings)).isEmpty();
            for (var kind : SloPolicy.ThroughputKind.values()) {
                // HTTP throughput need not equal iteration rate: an iteration can make multiple requests.
                assertThat(policy(percentile, kind, 25.5, SloPolicy.ThroughputOperator.GE).errors(settings)).isEmpty();
            }
        }
    }
    @Test void rejectsEveryIncompleteThroughputCombination() {
        for (int mask = 1; mask < 7; mask++) {
            assertThat(policy(SloPolicy.Percentile.P99, (mask & 1) != 0 ? SloPolicy.ThroughputKind.TOTAL : null,
                    (mask & 2) != 0 ? 5.0 : null, (mask & 4) != 0 ? SloPolicy.ThroughputOperator.GE : null).errors(settings)).hasSize(1);
        }
    }
    @Test void rejectsZeroNegativeAndExcessiveThroughputAndMissingPercentile() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (double value : new double[]{0, -1, 1000001})
                assertThat(validator.validate(policy(SloPolicy.Percentile.P90, SloPolicy.ThroughputKind.SUCCESSFUL, value, SloPolicy.ThroughputOperator.GE))).isNotEmpty();
            assertThat(validator.validate(policy(null, null, null, null))).isNotEmpty();
            assertThat(validator.validate(policy(SloPolicy.Percentile.P90, SloPolicy.ThroughputKind.SUCCESSFUL, 0.1, SloPolicy.ThroughputOperator.GE))).isEmpty();
        }
    }
    @Test void rejectsNonfiniteThresholdAndConflictingLegacyAliases() {
        assertThat(policy(SloPolicy.Percentile.P99, SloPolicy.ThroughputKind.TOTAL, Double.NaN, SloPolicy.ThroughputOperator.GE).errors(settings)).isNotEmpty();
        var conflict = new SloPolicy(SloPolicy.Percentile.P99, 251, 0.02, SloPolicy.SuccessDefinition.HTTP_200_399_WITHOUT_TRANSPORT_ERROR, null, null, null, java.util.List.of());
        assertThat(conflict.errors(settings)).hasSize(2);
    }
    @Test void validatesResourceUnitsScopePositiveBoundsAndUniqueness() {
        var cpu = new SloPolicy.ResourceCondition(SloPolicy.ResourceMetric.CPU_USAGE_MILLICORES, 250.5,
                SloPolicy.ResourceScope.SELECTED_CONTAINER_ALL_DEPLOYMENT_PODS,
                SloPolicy.ResourceAggregation.MAXIMUM_OF_POD_SUM_OVER_MEASUREMENT_WINDOW, SloPolicy.ResourceOperator.LE);
        var memory = new SloPolicy.ResourceCondition(SloPolicy.ResourceMetric.MEMORY_WORKING_SET_MIB, 128.0,
                cpu.scope(), cpu.aggregation(), cpu.operator());
        var valid = new SloPolicy(SloPolicy.Percentile.P95, 250, 0.01,
                SloPolicy.SuccessDefinition.HTTP_200_399_WITHOUT_TRANSPORT_ERROR, null, null, null, java.util.List.of(cpu, memory));
        assertThat(valid.errors(settings)).isEmpty();
        var duplicate = new SloPolicy(valid.latencyPercentile(), 250, 0.01, valid.successDefinition(), null, null, null, java.util.List.of(cpu, cpu));
        assertThat(duplicate.errors(settings)).contains("Configure each resource metric at most once.");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(valid)).isEmpty();
            for (double threshold : new double[]{0, -1, 1000000001}) {
                var bad = new SloPolicy.ResourceCondition(cpu.metric(), threshold, cpu.scope(), cpu.aggregation(), cpu.operator());
                assertThat(factory.getValidator().validate(bad)).isNotEmpty();
            }
            assertThat(factory.getValidator().validate(new SloPolicy.ResourceCondition(cpu.metric(), 1.0, null, cpu.aggregation(), cpu.operator()))).isNotEmpty();
        }
    }
}
