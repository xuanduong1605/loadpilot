package dev.loadpilot.experiments;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;

public record SloPolicy(@NotNull Percentile latencyPercentile,
                        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(600000) Integer latencyThresholdMs,
                        @NotNull @DecimalMin("0") @DecimalMax("1") Double maximumErrorRatio,
                        @NotNull SuccessDefinition successDefinition,
                        @JsonInclude(JsonInclude.Include.NON_NULL) ThroughputKind throughputKind,
                        @JsonInclude(JsonInclude.Include.NON_NULL) @DecimalMin(value="0", inclusive=false) @DecimalMax("1000000") Double minimumThroughputRps,
                        @JsonInclude(JsonInclude.Include.NON_NULL) ThroughputOperator throughputOperator,
                        @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=2) List<@NotNull @Valid ResourceCondition> resourceConditions) {
    public enum Percentile { P90, P95, P99 }
    public enum SuccessDefinition { HTTP_200_399_WITHOUT_TRANSPORT_ERROR }
    public enum ThroughputKind { TOTAL, SUCCESSFUL }
    public enum ThroughputOperator { GE }
    public enum ResourceMetric { CPU_USAGE_MILLICORES, MEMORY_WORKING_SET_MIB }
    public enum ResourceScope { SELECTED_CONTAINER_ALL_DEPLOYMENT_PODS }
    public enum ResourceAggregation { MAXIMUM_OF_POD_SUM_OVER_MEASUREMENT_WINDOW }
    public enum ResourceOperator { LE }
    public record ResourceCondition(@NotNull ResourceMetric metric,
                                    @NotNull @DecimalMin(value="0", inclusive=false) @DecimalMax("1000000000") Double threshold,
                                    @NotNull ResourceScope scope, @NotNull ResourceAggregation aggregation,
                                    @NotNull ResourceOperator operator) {}
    public List<String> errors(ExperimentSettingsApi.Settings settings) {
        List<String> errors = new ArrayList<>();
        if (!latencyThresholdMs.equals(settings.p95LatencyMs())) errors.add("The legacy latency-threshold field must match the selected SLO threshold.");
        if (!Double.isFinite(maximumErrorRatio) || Double.compare(maximumErrorRatio, settings.maximumErrorRatio()) != 0) errors.add("The error-ratio threshold must be finite and match the selected SLO.");
        boolean any = throughputKind != null || minimumThroughputRps != null || throughputOperator != null;
        boolean complete = throughputKind != null && minimumThroughputRps != null && throughputOperator != null;
        if (any && !complete) errors.add("Choose a throughput definition, positive threshold and >= operator together, or leave throughput unconfigured.");
        if (minimumThroughputRps != null && !Double.isFinite(minimumThroughputRps)) errors.add("The throughput threshold must be finite.");
        if (resourceConditions != null) {
            var metrics = new java.util.HashSet<ResourceMetric>();
            for (var condition : resourceConditions) {
                if (condition == null) continue; // Bean validation reports null entries.
                if (!metrics.add(condition.metric())) errors.add("Configure each resource metric at most once.");
                if (condition.threshold() != null && !Double.isFinite(condition.threshold())) errors.add("Resource thresholds must be finite.");
            }
        }
        return errors;
    }
}
