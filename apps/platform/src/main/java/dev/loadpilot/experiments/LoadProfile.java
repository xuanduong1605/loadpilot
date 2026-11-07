package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.ArrayList;
import java.util.List;

public record LoadProfile(@NotNull Mode mode, @NotNull Shape shape,
                          @JsonDeserialize(using=StrictInteger.class) @Min(0) @Max(10000) Integer startRate,
                          @NotNull @Size(max=12) List<@NotNull @Valid Stage> stages,
                          @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(3600) Integer measurementSeconds) {
    public enum Mode { ADAPTIVE_LOAD, SINGLE_RUN }
    public enum Shape { STEADY, RAMP, SPIKE }
    public record Stage(@JsonDeserialize(using=StrictInteger.class) @NotNull @Min(1) @Max(3600) Integer durationSeconds,
                        @JsonDeserialize(using=StrictInteger.class) @NotNull @Min(0) @Max(10000) Integer targetRate) {}
    public String executor() { return shape == Shape.STEADY ? "constant-arrival-rate" : "ramping-arrival-rate"; }
    public Integer measurementRate(ExperimentSettingsApi.Settings settings) {
        if (mode == Mode.ADAPTIVE_LOAD || startRate == null || stages == null) return null;
        long start = settings.warmupSeconds(), end = start + measurementSeconds, cursor = 0, covered = 0;
        int previous = startRate; Integer rate = null;
        for (var stage : stages) {
            long stageEnd = cursor + stage.durationSeconds();
            long overlap = Math.max(0, Math.min(end, stageEnd) - Math.max(start, cursor));
            if (overlap > 0) {
                if (previous != stage.targetRate() || previous <= 0 || (rate != null && rate != previous)) return null;
                rate = previous; covered += overlap;
            }
            cursor = stageEnd; previous = stage.targetRate();
        }
        return covered == measurementSeconds ? rate : null;
    }
    public List<String> errors(ExperimentSettingsApi.Settings settings) {
        List<String> errors = new ArrayList<>();
        if ((long) settings.warmupSeconds() + measurementSeconds > settings.durationSeconds()) errors.add("The measurement window must fit after warm-up and within the run duration.");
        if (mode == Mode.ADAPTIVE_LOAD) {
            if (shape != Shape.STEADY || startRate != null || !stages.isEmpty()) errors.add("Adaptive trials require a steady profile without fixed rates or stages.");
            if (measurementSeconds != settings.durationSeconds() - settings.warmupSeconds()) errors.add("Adaptive steady trials measure the complete post-warm-up window.");
            return errors;
        }
        if (startRate == null || stages.isEmpty()) { errors.add("Single-run profiles require a start rate and at least one stage."); return errors; }
        int previous = startRate, peak = startRate; long duration = 0; boolean increased = false, falling = false, malformedShape = false;
        if (startRate > settings.maximumRate() || (startRate > 0 && startRate < settings.minimumRate())) errors.add("Start rate must be zero or within the approved load bounds.");
        for (var stage : stages) {
            int rate = stage.targetRate(); duration += stage.durationSeconds(); peak = Math.max(peak, rate);
            if (rate > settings.maximumRate() || (rate > 0 && rate < settings.minimumRate())) errors.add("Stage rates must be zero or within the approved load bounds.");
            if (rate > previous) { increased = true; if (falling) malformedShape = true; }
            if (rate < previous) { falling = true; if (shape == Shape.RAMP) malformedShape = true; }
            previous = rate;
        }
        if (duration != settings.durationSeconds()) errors.add("Stage durations must sum exactly to the run duration, including warm-up and recovery.");
        if (shape == Shape.STEADY && (stages.size() != 1 || startRate <= 0 || previous != startRate)) errors.add("Steady profiles require one constant positive-rate stage.");
        if (shape == Shape.RAMP && (stages.size() < 2 || !increased || malformedShape)) errors.add("Ramp profiles must increase monotonically and include a steady peak plateau.");
        if (shape == Shape.SPIKE && (stages.size() < 3 || !increased || !falling || malformedShape || previous != startRate)) errors.add("Spike profiles require one rise and fall back to the starting rate.");
        Integer measured = measurementRate(settings);
        if (measured == null || measured != peak) errors.add("The measurement window must stay on a constant positive peak-rate plateau.");
        return errors;
    }
}
