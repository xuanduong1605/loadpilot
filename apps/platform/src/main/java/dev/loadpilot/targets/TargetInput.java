package dev.loadpilot.targets;

import jakarta.validation.constraints.*;

public record TargetInput(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\p{Cntrl}]+") String name,
        @NotBlank @Size(max = 2048) String baseUrl,
        @NotNull @Pattern(regexp = "DEVELOPMENT|STAGING|PRODUCTION") String environment,
        @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String namespace,
        @NotBlank @Size(max = 253) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*") String deployment,
        @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String container,
        @Pattern(regexp = "[A-Za-z0-9._-]{0,100}") String credentialRef,
        @Pattern(regexp = "[A-Za-z0-9._-]{0,100}") String monitoringRef,
        @Min(1) Long revision) {}
