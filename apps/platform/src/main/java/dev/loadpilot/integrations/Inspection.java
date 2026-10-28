package dev.loadpilot.integrations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Inspection(UUID targetId, long targetRevision, long connectionRevision, Instant collectedAt,
                         Check api, Check kubernetes, Check metrics, Check prometheus,
                         Workload workload, List<Pod> pods) {
    public record Check(String state, String message, Instant checkedAt, String source, Integer httpStatus) {}
    public record Workload(String uid, Long generation, Long observedGeneration, Integer desiredReplicas,
                           Integer readyReplicas, Integer availableReplicas, Integer updatedReplicas) {}
    public record Pod(String name, String phase, Boolean ready, Integer restarts,
                      BigDecimal cpuRequestMillicores, BigDecimal cpuLimitMillicores, BigDecimal memoryRequestBytes,
                      BigDecimal memoryLimitBytes, Usage usage) {}
    public record Usage(String state, Instant timestamp, String window, BigDecimal cpuMillicores, BigDecimal memoryBytes) {}
}
