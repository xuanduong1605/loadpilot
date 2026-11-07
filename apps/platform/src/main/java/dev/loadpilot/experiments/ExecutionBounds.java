package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.util.ArrayList;
import java.util.List;

public record ExecutionBounds(
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonDeserialize(using=StrictInteger.class) @Min(1) @Max(10000) Integer precisionIterationsPerSecond,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(1000) Integer preAllocatedVUs,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(1000) Integer maximumVUs,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(1) Integer workerCount,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(50) @Max(4000) Integer generatorCpuMillicores,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(64) @Max(4096) Integer generatorMemoryMiB,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(120000) Integer requestTimeoutMs,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(300) Integer startupTimeoutSeconds,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(0) @Max(60) Integer gracefulStopSeconds,
        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(3) Integer maximumAttemptsPerRun) {
    public long maximumAttemptSeconds(ExperimentSettingsApi.Settings settings) {
        return (long) startupTimeoutSeconds + settings.durationSeconds() + gracefulStopSeconds;
    }
    public int reservedAttemptsPerLoad(ExperimentSettingsApi.Settings settings) { return settings.repetitions() * maximumAttemptsPerRun; }
    public List<String> errors(ExperimentSettingsApi.Settings settings) {
        return errors(settings, null);
    }
    public List<String> errors(ExperimentSettingsApi.Settings settings, LoadProfile profile) {
        List<String> errors = new ArrayList<>();
        if ((profile == null || profile.mode() == LoadProfile.Mode.ADAPTIVE_LOAD) && precisionIterationsPerSecond == null) errors.add("Adaptive trials require an explicit search precision.");
        if (preAllocatedVUs > maximumVUs) errors.add("Preallocated VUs must not exceed maximum VUs.");
        long attempts = (long) settings.repetitions() * maximumAttemptsPerRun;
        if (attempts > settings.maximumRuns()) errors.add("The total attempt budget must cover repetitions and their configured retry allowance.");
        if (attempts * maximumAttemptSeconds(settings) > settings.totalBudgetSeconds()) errors.add("The time budget must cover repetitions, retries, startup and graceful stop for one tested load.");
        return errors;
    }
}
