package dev.loadpilot.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.targets.Target;
import dev.loadpilot.targets.TargetStore;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Service;
import static dev.loadpilot.integrations.Inspection.*;

@Service
public class SystemInspector {
    private final TargetStore targets;
    private final ConnectionStore connections;
    private final EndpointPolicy policy;
    private final ObjectMapper json;
    private final Semaphore capacity = new Semaphore(4);
    public SystemInspector(TargetStore targets, ConnectionStore connections, EndpointPolicy policy, ObjectMapper json) {
        this.targets = targets; this.connections = connections; this.policy = policy; this.json = json;
    }
    public Inspection inspect(UUID id) {
        if (!capacity.tryAcquire()) throw new ConnectionProblem(429, "INSPECTION_BUSY", "The platform is checking other systems. Try again shortly.");
        try {
            Target target = targets.get(id);
            if (target.archivedAt() != null) throw ConnectionProblem.conflict();
            Check api = probe(target.baseUrl(), "", "", false, "Registered API");
            ConnectionStore.Material config;
            try { config = connections.material(id); }
            catch (ConnectionProblem unavailable) {
                Check failed = failure(unavailable, "Connection credentials");
                return new Inspection(id, target.revision(), connections.view(id).revision(), Instant.now(), api, failed, failed, failed, null, List.of());
            }
            if (config == null) {
                Check missing = check("NOT_CONFIGURED", "Configure a connection to inspect this system.", "Configuration", null);
                return new Inspection(id, target.revision(), 0, Instant.now(), api, missing, missing, missing, null, List.of());
            }
            var kube = kubernetes(target, config);
            Check prom = config.prometheusUrl().isEmpty() ? check("NOT_CONFIGURED", "Prometheus is not configured.", "Prometheus", null)
                    : prometheus(config, target.namespace());
            return new Inspection(id, target.revision(), config.revision(), Instant.now(), api, kube.state, kube.metrics, prom, kube.workload, kube.pods);
        } finally { capacity.release(); }
    }
    private Check probe(String url, String ca, String token, boolean tls, String source) {
        try (var transport = new ReadOnlyHttp(policy, url, ca, token, tls)) {
            var response = transport.get(url);
            return http(response.status(), source);
        } catch (Exception error) { return failure(error, source); }
    }
    private Check prometheus(ConnectionStore.Material config, String namespace) {
        String base = config.prometheusUrl().replaceAll("/+$", "");
        try (var transport = new ReadOnlyHttp(policy, base, config.prometheusCa(), config.prometheusToken(), true)) {
            var ready = transport.get(base + "/-/ready");
            if (ready.status() != 200) return http(ready.status(), "Prometheus readiness");
            // Probe query availability without inventing application SLO mappings.
            String query = java.net.URLEncoder.encode("up{namespace=\"" + namespace + "\"}", java.nio.charset.StandardCharsets.UTF_8);
            var result = transport.get(base + "/api/v1/query?query=" + query);
            if (result.status() != 200) return http(result.status(), "Prometheus query API");
            JsonNode data = json.readTree(result.body());
            if (!"success".equals(data.path("status").asText()) || !data.path("data").path("result").isArray())
                return check("INVALID_RESPONSE", "Monitoring returned an invalid query response.", "Prometheus query API", 200);
            return check("READY", "Prometheus is ready; application metric mappings are not configured.", "Prometheus readiness/query API", 200);
        } catch (Exception error) { return failure(error, "Prometheus"); }
    }
    private record Kubernetes(Check state, Check metrics, Workload workload, List<Pod> pods) {}
    private Kubernetes kubernetes(Target target, ConnectionStore.Material config) {
        String source = "Kubernetes apps/v1 and core/v1";
        try (var transport = new ReadOnlyHttp(policy, config.kubernetesUrl(), config.kubernetesCa(), config.kubernetesToken(), true)) {
            var apps = new AppsV1Api(transport.api());
            V1Deployment deployment = apps.readNamespacedDeployment(target.deployment(), target.namespace()).execute();
            if (deployment.getMetadata() == null || deployment.getMetadata().getUid() == null || deployment.getSpec() == null
                    || !target.namespace().equals(deployment.getMetadata().getNamespace()) || !target.deployment().equals(deployment.getMetadata().getName()))
                throw new IllegalArgumentException();
            var sets = apps.listNamespacedReplicaSet(target.namespace()).limit(200).execute();
            var pods = new CoreV1Api(transport.api()).listNamespacedPod(target.namespace()).limit(200).execute();
            if ((sets.getMetadata() != null && sets.getMetadata().getContinue() != null && !sets.getMetadata().getContinue().isEmpty())
                    || (pods.getMetadata() != null && pods.getMetadata().getContinue() != null && !pods.getMetadata().getContinue().isEmpty())) {
                Check incomplete = check("INCOMPLETE", "Namespace exceeds the 200-object inspection limit. Narrow the inspection scope.", source, 200);
                return new Kubernetes(incomplete, incomplete, null, List.of());
            }
            String uid = deployment.getMetadata().getUid();
            Set<String> owners = new HashSet<>();
            for (V1ReplicaSet set : sets.getItems())
                if (owned(set.getMetadata(), uid, "Deployment") && target.namespace().equals(set.getMetadata().getNamespace()) && set.getMetadata().getUid() != null) owners.add(set.getMetadata().getUid());
            Map<String, JsonNode> samples = new HashMap<>();
            Check metricState;
            try {
                var result = transport.get(config.kubernetesUrl().replaceAll("/+$", "") + "/apis/metrics.k8s.io/v1beta1/namespaces/" + target.namespace() + "/pods");
                if (result.status() != 200) metricState = http(result.status(), "Kubernetes PodMetrics");
                else {
                    JsonNode metrics = json.readTree(result.body());
                    if (!metrics.path("items").isArray() || metrics.path("items").size() > 200) throw new IllegalArgumentException();
                    for (JsonNode sample : metrics.path("items")) {
                        if (!target.namespace().equals(sample.path("metadata").path("namespace").asText())) continue;
                        String name = sample.path("metadata").path("name").asText();
                        if (samples.putIfAbsent(name, sample) != null) throw new IllegalArgumentException();
                    }
                    metricState = check("READY", "Current selected-container samples.", "Kubernetes PodMetrics", 200);
                }
            } catch (Exception error) { metricState = failure(error, "Kubernetes PodMetrics"); samples.clear(); }
            List<Pod> selected = new ArrayList<>();
            for (V1Pod pod : pods.getItems()) {
                if (pod.getMetadata() == null || !target.namespace().equals(pod.getMetadata().getNamespace())
                        || owners.stream().noneMatch(owner -> owned(pod.getMetadata(), owner, "ReplicaSet"))) continue;
                if (pod.getSpec() == null) throw new IllegalArgumentException();
                V1Container container = pod.getSpec().getContainers().stream().filter(c -> target.container().equals(c.getName())).findFirst().orElse(null);
                if (container == null) continue;
                var resources = container.getResources();
                var state = pod.getStatus();
                V1ContainerStatus containerState = state == null || state.getContainerStatuses() == null ? null
                        : state.getContainerStatuses().stream().filter(c -> target.container().equals(c.getName())).findFirst().orElse(null);
                Usage usage = UsageParser.parse(samples.get(pod.getMetadata().getName()), target.namespace(), target.container(), Instant.now());
                selected.add(new Pod(pod.getMetadata().getName(), state == null ? null : state.getPhase(), containerState == null ? null : containerState.getReady(),
                        containerState == null ? null : containerState.getRestartCount(),
                        quantity(resources == null ? null : resources.getRequests(), "cpu", true), quantity(resources == null ? null : resources.getLimits(), "cpu", true),
                        quantity(resources == null ? null : resources.getRequests(), "memory", false), quantity(resources == null ? null : resources.getLimits(), "memory", false), usage));
            }
            if ("READY".equals(metricState.state()) && (selected.isEmpty() || selected.stream().anyMatch(pod -> !"READY".equals(pod.usage().state()))))
                metricState = check("INCOMPLETE", "One or more selected-container samples are missing, stale or invalid.", "Kubernetes PodMetrics", 200);
            var status = deployment.getStatus();
            Workload workload = new Workload(uid, deployment.getMetadata().getGeneration(), status == null ? null : status.getObservedGeneration(), deployment.getSpec().getReplicas(),
                    status == null ? null : status.getReadyReplicas(), status == null ? null : status.getAvailableReplicas(), status == null ? null : status.getUpdatedReplicas());
            boolean exists = deployment.getSpec().getTemplate().getSpec() != null && deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                    .anyMatch(c -> target.container().equals(c.getName()));
            return new Kubernetes(check(exists ? "READY" : "CONTAINER_NOT_FOUND", exists ? "Workload inspected; readiness is shown separately." : "The registered container is absent from this Deployment.", source, 200),
                    metricState, workload, selected);
        } catch (Exception error) {
            Check failed = failure(error, source);
            return new Kubernetes(failed, check("UNAVAILABLE", "Resource metrics need a successful Kubernetes inspection.", "Kubernetes PodMetrics", null), null, List.of());
        }
    }
    private boolean owned(V1ObjectMeta metadata, String uid, String kind) {
        return metadata != null && metadata.getOwnerReferences() != null && metadata.getOwnerReferences().stream()
                .anyMatch(owner -> uid.equals(owner.getUid()) && kind.equals(owner.getKind()) && Boolean.TRUE.equals(owner.getController()));
    }
    private BigDecimal quantity(Map<String, Quantity> values, String resource, boolean cpu) {
        if (values == null || values.get(resource) == null) return null;
        BigDecimal number = values.get(resource).getNumber();
        return number.signum() < 0 ? null : cpu ? number.multiply(BigDecimal.valueOf(1000)) : number;
    }
    private Check check(String state, String message, String source, Integer status) { return new Check(state, message, Instant.now(), source, status); }
    private Check http(int status, String source) {
        return check(status >= 200 && status < 300 ? "READY" : status == 401 ? "UNAUTHORIZED" : status == 403 ? "FORBIDDEN" : status == 404 ? "NOT_FOUND"
                : status >= 300 && status < 400 ? "REDIRECT_BLOCKED" : "HTTP_ERROR", "HTTP " + status + " from " + source + ".", source, status);
    }
    private Check failure(Exception error, String source) {
        if (error instanceof ConnectionProblem policy) return check(policy.code(), policy.getMessage(), source, null);
        if (error instanceof ApiException api && api.getCode() > 0) return http(api.getCode(), source);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ReadOnlyHttp.ResponseLimitException) return check("RESPONSE_TOO_LARGE", "Response exceeded the inspection limit.", source, null);
            if (cause instanceof SSLException) return check("TLS_ERROR", "TLS validation failed. Check the endpoint name and certificate authority.", source, null);
            if (cause instanceof InterruptedIOException) return check("TIMEOUT", "The endpoint did not respond within the inspection deadline.", source, null);
        }
        boolean io = error instanceof IOException || (error instanceof ApiException api && api.getCode() == 0 && api.getCause() instanceof IOException);
        return check(io ? "OFFLINE" : "INVALID_RESPONSE", io ? "Cannot reach this endpoint from the platform." : "The endpoint returned an invalid inspection response.", source, null);
    }
}
