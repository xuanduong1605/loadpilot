package dev.loadpilot.targets;

import java.time.Instant;
import java.util.UUID;

public record Target(UUID id, String name, String baseUrl, String environment, String namespace,
                     String deployment, String container, String credentialRef, String monitoringRef,
                     long revision, Instant createdAt, Instant updatedAt, Instant archivedAt) {}
