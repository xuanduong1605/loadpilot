package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.*;
import dev.loadpilot.experiments.ExperimentSnapshots;
import dev.loadpilot.experiments.RunDispatch;
import io.kubernetes.client.openapi.JSON;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Stable exact plan bytes; sealing these bytes does not grant execution authority. */
public final class K6PlanEnvelope {
    public record Prepared(String canonicalJson, String sha256) {}
    private final ObjectMapper canonical;
    private final K6JobPlan planner;
    public K6PlanEnvelope(ObjectMapper json, Validator validator) {
        canonical=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        planner=new K6JobPlan(json,validator);
    }
    public Prepared prepare(ExperimentSnapshots.Snapshot snapshot, RunDispatch.Ticket ticket,
                            K6JobPlan.Policy policy, K6JobPlan.PinnedTarget target, Instant now) {
        return prepare(snapshot,ticket,policy,target,null,now);
    }
    public Prepared prepare(ExperimentSnapshots.Snapshot snapshot,RunDispatch.Ticket ticket,K6JobPlan.Policy policy,K6JobPlan.PinnedTarget target,ExecutionClusterClients.Binding binding,Instant now) {
        var plan=planner.construct(snapshot,ticket,policy,target,now);
        var values=new TreeMap<String,Object>();
        values.put("schemaVersion",binding==null?"k6-execution-plan-v1":"k6-execution-plan-v2");values.put("plannerVersion","job-planner-v6");
        values.put("workerClock",Map.of("version",K6RuntimeGuard.VERSION,"submissionStartedAt",ticket.submissionStartedAt().toInstant().toString(),"startupTimeoutSeconds",snapshot.inputs().executionBounds().startupTimeoutSeconds(),"terminationGraceReserved",true));
        values.put("requiredSourcePolicy",Map.of("version",ExecutionSourcePolicy.VERSION,"requestTimeoutMs",snapshot.inputs().executionBounds().requestTimeoutMs(),"redirects",0,"originScope","REVIEWED_ORIGIN_ONLY"));
        if(binding!=null) {
            if(!snapshot.inputs().target().id().equals(binding.targetId()) || !policy.namespace().equals(binding.namespace()) || binding.connectionRevision()<1) throw new IllegalArgumentException("Cluster binding differs from reviewed inputs.");
            values.put("clusterBinding",Map.of("targetId",binding.targetId().toString(),"connectionRevision",binding.connectionRevision(),"origin",binding.origin(),"addresses",binding.addresses(),"namespace",binding.namespace(),"operations",binding.operations(),"caSha256",binding.caSha256()));
        }
        values.put("snapshotId",snapshot.id().toString());values.put("snapshotSha256",snapshot.sha256());
        values.put("scriptSha256",snapshot.inputs().scenario().script().sha256());
        values.put("runId",ticket.runId().toString());values.put("runRevision",ticket.revision());
        values.put("attemptId",ticket.attemptId().toString());values.put("shardId",ticket.shardId().toString());
        values.put("submitOperationId",ticket.submitOperationId().toString());values.put("workerName",ticket.workerName());
        values.put("maximumSeconds",ticket.maximumSeconds());values.put("deadlineAt",ticket.deadlineAt().toInstant().toString());
        values.put("policy",Map.of("namespace",policy.namespace(),"imageDigest",policy.imageDigest(),"serviceAccount",policy.serviceAccount(),"runtimeClass",policy.runtimeClass(),"temporaryStorageMiB",policy.temporaryStorageMiB()));
        values.put("destination",Map.of("host",target.host(),"ip",target.ip(),"port",target.port()));
        values.put("submissionReady",false);
        try {
            // Use Kubernetes serializers for API fields/quantities, then canonicalize all map keys recursively.
            values.put("job",canonical.readValue(JSON.serialize(plan.job()),Object.class));
            values.put("files",canonical.readValue(JSON.serialize(plan.files()),Object.class));
            values.put("network",canonical.readValue(JSON.serialize(plan.network()),Object.class));
            values.put("options",canonical.readValue(plan.optionsJson(),Object.class));
            String text=canonical.writeValueAsString(values);
            if(text.getBytes(StandardCharsets.UTF_8).length>524288) throw new IllegalArgumentException("Execution plan exceeds the 512 KiB limit.");
            return new Prepared(text,hash(text));
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Cannot serialize execution plan.",invalid); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
