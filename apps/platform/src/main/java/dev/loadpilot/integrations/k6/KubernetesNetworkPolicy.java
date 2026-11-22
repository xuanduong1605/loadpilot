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
        // Normalize only optional empty lists that Kubernetes omits on readback.
        ObjectNode normalized=spec.deepCopy();
        omitEmptyList(normalized.path("podSelector"),"matchExpressions");
        for(String field:List.of("ingress","egress")) {
            omitEmptyList(normalized,field);
            String peers=field.equals("ingress")?"from":"to";
            for(var rule:normalized.path(field)) for(var peer:rule.path(peers)) {
                omitEmptyList(peer.path("podSelector"),"matchExpressions");
                omitEmptyList(peer.path("namespaceSelector"),"matchExpressions");
                omitEmptyList(peer.path("ipBlock"),"except");
            }
        }
        return normalized;
    }

    private static void omitEmptyList(JsonNode parent,String field) {
        if(parent instanceof ObjectNode object && object.path(field).isArray() && object.path(field).isEmpty())object.remove(field);
    }
}
