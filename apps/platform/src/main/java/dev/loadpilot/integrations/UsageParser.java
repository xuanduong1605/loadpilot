package dev.loadpilot.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import io.kubernetes.client.custom.Quantity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Pattern;
import static dev.loadpilot.integrations.Inspection.Usage;

public final class UsageParser {
    private UsageParser() {}
    private static final Pattern WINDOW = Pattern.compile("(?=.+)(?:[0-9]{1,4}h)?(?:[0-9]{1,4}m)?(?:[0-9]{1,4}(?:\\.[0-9]{1,9})?s)?");
    public static Usage parse(JsonNode sample, String namespace, String container, Instant now) {
        if (sample == null) return new Usage("MISSING", null, null, null, null);
        Instant timestamp = null;
        String window = sample.path("window").asText("");
        try {
            timestamp = Instant.parse(sample.path("timestamp").asText());
            if (!namespace.equals(sample.path("metadata").path("namespace").asText()) || !WINDOW.matcher(window).matches()
                    || !window.matches(".*[1-9].*") || timestamp.isAfter(now.plusSeconds(5))) throw new IllegalArgumentException();
            if (timestamp.isBefore(now.minusSeconds(120))) return new Usage("STALE", timestamp, window, null, null);
            JsonNode chosen = null;
            for (JsonNode candidate : sample.path("containers")) if (container.equals(candidate.path("name").asText())) {
                if (chosen != null) throw new IllegalArgumentException();
                chosen = candidate;
            }
            if (chosen == null) return new Usage("MISSING", timestamp, window, null, null);
            String cpu = chosen.path("usage").path("cpu").asText("");
            String memory = chosen.path("usage").path("memory").asText("");
            if (!cpu.matches("[0-9]{1,20}(?:\\.[0-9]{1,20})?(?:[numkKMGTPE]|[KMGTPE]i|[eE][+\\-]?[0-9]{1,2})?")
                    || !memory.matches("[0-9]{1,20}(?:\\.[0-9]{1,20})?(?:[numkKMGTPE]|[KMGTPE]i|[eE][+\\-]?[0-9]{1,2})?"))
                throw new IllegalArgumentException();
            BigDecimal cpuValue = new Quantity(cpu).getNumber();
            BigDecimal memoryValue = new Quantity(memory).getNumber();
            if (cpuValue.signum() < 0 || memoryValue.signum() < 0 || cpuValue.compareTo(new BigDecimal("1e18")) > 0
                    || memoryValue.compareTo(new BigDecimal("1e18")) > 0) throw new IllegalArgumentException();
            return new Usage("READY", timestamp, window, cpuValue.multiply(BigDecimal.valueOf(1000)), memoryValue);
        } catch (RuntimeException invalid) { return new Usage("INVALID", timestamp, null, null, null); }
    }
}
