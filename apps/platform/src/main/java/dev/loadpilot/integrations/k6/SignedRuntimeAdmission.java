package dev.loadpilot.integrations.k6;

import java.time.*;
import java.util.Objects;

/** Signature authenticates probe results; current readback prevents replay after node/CNI/policy drift. */
public final class SignedRuntimeAdmission implements RuntimeAdmission {
    private final SignedRuntimeEvidence evidence;
    private final KubernetesRuntimeState state;
    private final Clock clock;
    public SignedRuntimeAdmission(SignedRuntimeEvidence evidence,KubernetesRuntimeState state,Clock clock){this.evidence=evidence;this.state=state;this.clock=clock;}
    public boolean available(){return evidence.configured() && state!=null;}
    public Grant verify(K6PlanEnvelope.Prepared plan){
        if(!available())throw SignedRuntimeEvidence.denied();
        var verified=evidence.load(plan,clock.instant());var report=verified.report();
        String first=state.read(plan,report.nodes()),last=state.read(plan,report.nodes());
        if(!first.equals(last) || !first.equals(report.stateSha256()) || !report.validUntil().isAfter(clock.instant()))throw SignedRuntimeEvidence.denied();
        return new Grant(report.verificationId(),plan.sha256(),report.validUntil(),verified.sha256());
    }
    public void recheck(K6PlanEnvelope.Prepared plan,Grant grant){
        if(grant==null || !Objects.equals(grant.planSha256(),plan.sha256()) || !grant.equals(verify(plan)))throw SignedRuntimeEvidence.denied();
    }
}
