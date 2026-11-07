package dev.loadpilot.experiments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/experiments/settings")
public class ExperimentSettingsApi {
    public record Settings(
            @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(10000) Integer minimumRate,
            @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(10000) Integer maximumRate,
            @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(3600) Integer durationSeconds,
            @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(0) @Max(3599) Integer warmupSeconds,
            @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(600000) Integer p95LatencyMs,
            @NotNull @DecimalMin("0") @DecimalMax("1") Double maximumErrorRatio,
            @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(20) Integer repetitions,
            @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(100) Integer maximumRuns,
            @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(86400) Integer totalBudgetSeconds) {}
    public record Review(boolean valid, List<String> errors, Settings settings,
                         String loadUnit, String executor) {}
    public record EffectiveInput(@NotNull @Valid Settings settings, @NotNull @Valid ExecutionBounds executionBounds, @NotNull @Valid LoadProfile loadProfile, @NotNull @Valid SloPolicy sloPolicy) {}
    public record EffectiveReview(boolean valid, List<String> errors, Settings settings, ExecutionBounds executionBounds, LoadProfile loadProfile, SloPolicy sloPolicy,
                                  long maximumAttemptSeconds, int reservedAttemptsPerLoad) {}
    @PostMapping("/validate-effective")
    public EffectiveReview validateEffective(@Valid @RequestBody EffectiveInput input) {
        var errors = new ArrayList<>(validate(input.settings()).errors());
        errors.addAll(input.executionBounds().errors(input.settings(), input.loadProfile()));
        errors.addAll(input.loadProfile().errors(input.settings()));
        errors.addAll(input.sloPolicy().errors(input.settings()));
        if (input.sloPolicy().resourceConditions() == null) errors.add("Explicitly choose resource conditions or no resource SLO.");
        return new EffectiveReview(errors.isEmpty(), List.copyOf(errors), input.settings(), input.executionBounds(), input.loadProfile(), input.sloPolicy(),
                input.executionBounds().maximumAttemptSeconds(input.settings()), input.executionBounds().reservedAttemptsPerLoad(input.settings()));
    }

    @PostMapping("/validate")
    public Review validate(@Valid @RequestBody Settings input) {
        List<String> errors = new ArrayList<>();
        if (input.minimumRate() > input.maximumRate()) errors.add("Minimum rate must not exceed maximum rate.");
        if (input.warmupSeconds() >= input.durationSeconds()) errors.add("Warm-up must be shorter than run duration.");
        if (!Double.isFinite(input.maximumErrorRatio())) errors.add("Error ratio must be finite.");
        if (input.repetitions() > input.maximumRuns()) errors.add("Run budget must cover all repetitions of a tested load.");
        if ((long) input.durationSeconds() * input.repetitions() > input.totalBudgetSeconds())
            errors.add("Time budget must cover all repetitions of a tested load, including warm-up.");
        return new Review(errors.isEmpty(), List.copyOf(errors), input, "iterations/s", "constant-arrival-rate");
    }
}
