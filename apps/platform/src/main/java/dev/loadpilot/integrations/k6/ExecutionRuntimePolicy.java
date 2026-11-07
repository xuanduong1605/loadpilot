package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operator declaration, not proof that an image, namespace or runtime has been inspected. */
@Component
public class ExecutionRuntimePolicy {
    public static final class Problem extends RuntimeException {
        public Problem() { super("No execution runtime policy is configured."); }
        public int status() { return 503; }
        public String code() { return "EXECUTION_RUNTIME_UNCONFIGURED"; }
    }
    private final K6JobPlan.Policy policy;
    @Autowired public ExecutionRuntimePolicy(@Value("${platform.execution.runtime-policy-json:}") String config,ObjectMapper json) {
        this(parse(config,json));
    }
    public ExecutionRuntimePolicy(K6JobPlan.Policy policy) {
        if(policy!=null) {
            try { K6JobPlan.validatePolicy(policy); }
            catch(RuntimeException invalid) { throw invalidConfig(); }
        }
        this.policy=policy;
    }
    public K6JobPlan.Policy current() { if(policy==null) throw new Problem();return policy; }
    private static K6JobPlan.Policy parse(String config,ObjectMapper json) {
        if(config==null || config.isBlank()) return null;
        if(config.getBytes(StandardCharsets.UTF_8).length>8192) throw invalidConfig();
        try {
            var body=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(config);
            var keys=Set.of("namespace","imageDigest","serviceAccount","runtimeClass","temporaryStorageMiB");
            if(!body.isObject() || !body.properties().stream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(keys)) throw invalidConfig();
            for(String name:Set.of("namespace","imageDigest","serviceAccount","runtimeClass")) if(!body.path(name).isTextual()) throw invalidConfig();
            var storage=body.path("temporaryStorageMiB");if(!storage.isIntegralNumber() || !storage.canConvertToInt()) throw invalidConfig();
            return new K6JobPlan.Policy(body.path("namespace").asText(),body.path("imageDigest").asText(),body.path("serviceAccount").asText(),body.path("runtimeClass").asText(),storage.intValue());
        } catch(Exception invalid) { throw invalidConfig(); }
    }
    private static IllegalArgumentException invalidConfig() { return new IllegalArgumentException("Execution runtime policy requires exactly namespace, digest image, non-default service account, runtime class and integer storage limit."); }
}
