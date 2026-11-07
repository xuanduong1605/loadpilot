package dev.loadpilot.integrations.k6;

import java.time.Instant;
import java.util.UUID;

/** Runtime verifier boundary. A policy declaration or API create acknowledgement is not admission evidence. */
public interface RuntimeAdmission {
    record Grant(UUID verificationId,String planSha256,Instant validUntil,String evidenceSha256) {}
    boolean available();
    Grant verify(K6PlanEnvelope.Prepared plan);
    void recheck(K6PlanEnvelope.Prepared plan,Grant grant);
}
