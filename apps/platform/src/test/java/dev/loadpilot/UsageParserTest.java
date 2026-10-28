package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.UsageParser;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UsageParserTest {
    @Test void rejectsWrongScopeFutureTimeNegativeMissingDuplicateOrInvalidQuantities() throws Exception {
        var json = new ObjectMapper(); var now = Instant.parse("2026-10-27T00:00:00Z");
        var valid = json.readTree("{\"metadata\":{\"namespace\":\"fixture\"},\"timestamp\":\"2026-10-27T00:00:00Z\",\"window\":\"30s\",\"containers\":[{\"name\":\"api\",\"usage\":{\"cpu\":\"50m\",\"memory\":\"64Mi\"}}]}");
        assertThat(UsageParser.parse(valid,"fixture","api",now).cpuMillicores()).isEqualByComparingTo("50");
        assertThat(UsageParser.parse(valid,"wrong","api",now).state()).isEqualTo("INVALID");
        assertThat(UsageParser.parse(valid,"fixture","missing",now).state()).isEqualTo("MISSING");
        assertThat(UsageParser.parse(valid,"fixture","api",now.minusSeconds(10)).state()).isEqualTo("INVALID");
        assertThat(UsageParser.parse(valid,"fixture","api",now.plusSeconds(121)).state()).isEqualTo("STALE");
        for (String cpu : new String[]{"-1m","NaN","1e99",""}) {
            var sample = valid.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)sample.path("containers").get(0).path("usage")).put("cpu",cpu);
            assertThat(UsageParser.parse(sample,"fixture","api",now).cpuMillicores()).isNull();
        }
        var duplicate = valid.deepCopy(); ((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("containers")).add(valid.path("containers").get(0));
        assertThat(UsageParser.parse(duplicate,"fixture","api",now).state()).isEqualTo("INVALID");
    }
}
