package dev.loadpilot;

import dev.loadpilot.experiments.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoadProfileTest {
    ExperimentSettingsApi.Settings settings(int warmup) { return new ExperimentSettingsApi.Settings(1, 5, 20, warmup, 300, 0.01, 1, 3, 90); }
    LoadProfile profile(LoadProfile.Shape shape, int start, int measurement, int[]... stages) {
        return new LoadProfile(LoadProfile.Mode.SINGLE_RUN, shape, start, java.util.Arrays.stream(stages).map(s -> new LoadProfile.Stage(s[0], s[1])).toList(), measurement);
    }
    @Test void steadyAndRampRequireExactDurationAndMeasuredPeakPlateau() {
        var steady = profile(LoadProfile.Shape.STEADY, 3, 15, new int[]{20, 3});
        assertThat(steady.errors(settings(5))).isEmpty(); assertThat(steady.measurementRate(settings(5))).isEqualTo(3);
        var ramp = profile(LoadProfile.Shape.RAMP, 1, 15, new int[]{5, 5}, new int[]{15, 5});
        assertThat(ramp.errors(settings(5))).isEmpty();
        assertThat(ramp.errors(settings(4))).isNotEmpty(); // Window intersects increasing load.
        assertThat(profile(LoadProfile.Shape.STEADY, 3, 15, new int[]{19, 3}).errors(settings(5))).isNotEmpty();
    }
    @Test void spikeMeasuresPeakNotRecoveryAndCanSpanAdjacentPeakHolds() {
        var spike = profile(LoadProfile.Shape.SPIKE, 1, 8, new int[]{2, 5}, new int[]{3, 5}, new int[]{5, 5}, new int[]{2, 1}, new int[]{8, 1});
        assertThat(spike.errors(settings(2))).isEmpty(); assertThat(spike.measurementRate(settings(2))).isEqualTo(5);
        assertThat(spike.errors(settings(12))).contains("The measurement window must stay on a constant positive peak-rate plateau.");
    }
    @Test void rejectsZeroMeasuredLoadOutOfBoundsAndRepeatedSpikes() {
        assertThat(profile(LoadProfile.Shape.STEADY, 0, 15, new int[]{20, 0}).errors(settings(5))).isNotEmpty();
        assertThat(profile(LoadProfile.Shape.STEADY, 6, 15, new int[]{20, 6}).errors(settings(5))).isNotEmpty();
        var repeated = profile(LoadProfile.Shape.SPIKE, 1, 4, new int[]{2, 5}, new int[]{4, 5}, new int[]{2, 1}, new int[]{2, 5}, new int[]{4, 5}, new int[]{2, 1}, new int[]{4, 1});
        assertThat(repeated.errors(settings(2))).contains("Spike profiles require one rise and fall back to the starting rate.");
    }
    @Test void adaptiveProfilesHaveNoInventedRateAndRequireExplicitPrecision() {
        var adaptive = new LoadProfile(LoadProfile.Mode.ADAPTIVE_LOAD, LoadProfile.Shape.STEADY, null, List.of(), 15);
        assertThat(adaptive.errors(settings(5))).isEmpty(); assertThat(adaptive.measurementRate(settings(5))).isNull();
        var noPrecision = new ExecutionBounds(null, 1, 2, 1, 250, 128, 1000, 3, 2, 1);
        assertThat(noPrecision.errors(settings(5), adaptive)).isNotEmpty();
        assertThat(noPrecision.errors(settings(5), profile(LoadProfile.Shape.STEADY, 3, 15, new int[]{20, 3}))).isEmpty();
        assertThat(new LoadProfile(LoadProfile.Mode.ADAPTIVE_LOAD, LoadProfile.Shape.RAMP, 1, List.of(new LoadProfile.Stage(20, 5)), 15).errors(settings(5))).isNotEmpty();
    }
}
