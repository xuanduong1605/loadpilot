package dev.loadpilot.integrations;

import jakarta.validation.constraints.*;

public record ConnectionInput(@Min(0) long revision, @Min(1) long targetRevision,
        @NotBlank @Size(max=2048) String kubernetesUrl, @NotNull @Size(max=16384) String kubernetesCa,
        @NotNull @Size(max=8192) @Pattern(regexp="[A-Za-z0-9._~+/=\\-]*") String kubernetesToken,
        @NotNull @Size(max=2048) String prometheusUrl, @NotNull @Size(max=16384) String prometheusCa,
        @NotNull @Size(max=8192) @Pattern(regexp="[A-Za-z0-9._~+/=\\-]*") String prometheusToken,
        @AssertTrue boolean readOnlyAcknowledged) {
    @Override public String toString() { return "ConnectionInput[credentials=redacted]"; }
}
