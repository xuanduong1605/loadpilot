package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.V1NetworkPolicySpec;
import java.util.List;

final class KubernetesNetworkPolicy {
    private KubernetesNetworkPolicy() {}

    static boolean matches(ObjectMapper json,V1NetworkPolicySpec expected,V1NetworkPolicySpec observed) {
        try {
            return matches(json.readTree(JSON.serialize(expected)),json.readTree(JSON.serialize(observed)));
        } catch(JsonProcessingException invalid) {
            return false;
        }
    }

    static boolean matches(JsonNode expected,JsonNode observed) {
        return expected instanceof ObjectNode wanted && observed instanceof ObjectNode actual
                && omitEmptyRules(wanted).equals(omitEmptyRules(actual));
    }

    private static ObjectNode omitEmptyRules(ObjectNode spec) {
        // The API omits empty ingress/egress lists. Selectors, policy types and nonempty rules still match exactly.
        ObjectNode normalized=spec.deepCopy();
        for(String field:List.of("ingress","egress")) {
            JsonNode rules=normalized.path(field);
            if(rules.isArray() && rules.isEmpty())normalized.remove(field);
        }
        return normalized;
    }
}
