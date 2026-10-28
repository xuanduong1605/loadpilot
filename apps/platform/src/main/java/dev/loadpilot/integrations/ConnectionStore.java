package dev.loadpilot.integrations;

import dev.loadpilot.audit.TargetAudit;
import dev.loadpilot.targets.TargetStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectionStore {
    private final JdbcTemplate database;
    private final TargetStore targets;
    private final TargetAudit audit;
    private final SecretCipher cipher;
    private final EndpointPolicy policy;
    public ConnectionStore(JdbcTemplate database, TargetStore targets, TargetAudit audit, SecretCipher cipher, EndpointPolicy policy) {
        this.database = database; this.targets = targets; this.audit = audit; this.cipher = cipher; this.policy = policy;
    }
    public record View(long revision, String kubernetesUrl, String kubernetesCa, boolean hasKubernetesToken,
                       String prometheusUrl, String prometheusCa, boolean hasPrometheusToken, Instant updatedAt) {}
    public record Material(long revision, String kubernetesUrl, String kubernetesCa, String kubernetesToken,
                           String prometheusUrl, String prometheusCa, String prometheusToken) {
        @Override public String toString() { return "ConnectionMaterial[credentials=redacted]"; }
    }
    private record Stored(long revision, String kubeUrl, String kubeCa, String kubeToken, String promUrl, String promCa, String promToken, Instant updated) {
        View view() { return new View(revision, kubeUrl, kubeCa, !kubeToken.isEmpty(), promUrl, promCa, !promToken.isEmpty(), updated); }
    }
    private Stored read(UUID id) {
        return database.query("SELECT * FROM system_connection WHERE target_id=?", (row, index) -> new Stored(
                row.getLong("revision"), row.getString("kubernetes_url"), row.getString("kubernetes_ca"), row.getString("kubernetes_token"),
                row.getString("prometheus_url"), row.getString("prometheus_ca"), row.getString("prometheus_token"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant()), id).stream().findFirst().orElse(null);
    }
    public View view(UUID id) {
        targets.get(id);
        Stored stored = read(id);
        return stored == null ? new View(0, "", "", false, "", "", false, null) : stored.view();
    }
    public Material material(UUID id) {
        Stored stored = read(id);
        if (stored == null) return null;
        return new Material(stored.revision, stored.kubeUrl, stored.kubeCa, cipher.open(stored.kubeToken, id + ":kube"),
                stored.promUrl, stored.promCa, stored.promToken.isEmpty() ? "" : cipher.open(stored.promToken, id + ":prom"));
    }
    private void certificate(String value) {
        if (value.isBlank()) return;
        // CA fields are returned as public metadata; never accept private-key or kubeconfig material here.
        if (!value.strip().matches("(?s)(?:-----BEGIN CERTIFICATE-----\\s*[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----\\s*)+"))
            throw ConnectionProblem.invalid();
        try {
            if (CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII))).isEmpty())
                throw ConnectionProblem.invalid();
        } catch (java.security.cert.CertificateException invalid) { throw ConnectionProblem.invalid(); }
    }
    @Transactional
    public View save(UUID id, ConnectionInput input, String actor) {
        var target = targets.get(id);
        if (target.archivedAt() != null || target.revision() != input.targetRevision()) throw ConnectionProblem.conflict();
        if (!cipher.available()) throw ConnectionProblem.unavailable();
        var kubeUri = policy.validate(input.kubernetesUrl(), true);
        if (kubeUri.getPath() != null && !kubeUri.getPath().isEmpty() && !"/".equals(kubeUri.getPath())) throw ConnectionProblem.invalid();
        if (!input.prometheusUrl().isBlank()) policy.validate(input.prometheusUrl(), true);
        certificate(input.kubernetesCa()); certificate(input.prometheusCa());
        Stored previous = read(id);
        if ((previous == null ? 0 : previous.revision) != input.revision()) throw ConnectionProblem.conflict();
        if (previous != null && ((!origin(previous.kubeUrl).equals(origin(input.kubernetesUrl())) && input.kubernetesToken().isEmpty())
                || (!input.prometheusUrl().isBlank() && !previous.promToken.isEmpty()
                && !origin(previous.promUrl).equals(origin(input.prometheusUrl())) && input.prometheusToken().isEmpty())))
            throw new ConnectionProblem(400,"REPLACEMENT_TOKEN_REQUIRED","Enter a replacement token when changing an authenticated endpoint.");
        String kubeToken = input.kubernetesToken().isEmpty() ? previous == null ? "" : previous.kubeToken : cipher.seal(input.kubernetesToken(), id + ":kube");
        if (kubeToken.isEmpty()) throw ConnectionProblem.invalid();
        String promToken = input.prometheusUrl().isBlank() ? "" : input.prometheusToken().isEmpty()
                ? previous == null ? "" : previous.promToken : cipher.seal(input.prometheusToken(), id + ":prom");
        if (previous == null) {
            try {
                database.update("INSERT INTO system_connection(target_id,kubernetes_url,kubernetes_ca,kubernetes_token,prometheus_url,prometheus_ca,prometheus_token) VALUES (?,?,?,?,?,?,?)",
                        id, input.kubernetesUrl(), input.kubernetesCa(), kubeToken, input.prometheusUrl(), input.prometheusCa(), promToken);
            } catch (DuplicateKeyException conflict) { throw ConnectionProblem.conflict(); }
        } else if (database.update("UPDATE system_connection SET revision=revision+1,kubernetes_url=?,kubernetes_ca=?,kubernetes_token=?,prometheus_url=?,prometheus_ca=?,prometheus_token=?,updated_at=CURRENT_TIMESTAMP WHERE target_id=? AND revision=?",
                input.kubernetesUrl(), input.kubernetesCa(), kubeToken, input.prometheusUrl(), input.prometheusCa(), promToken, id, input.revision()) == 0)
            throw ConnectionProblem.conflict();
        targets.bindConnection(id, input.targetRevision(), !input.prometheusUrl().isBlank());
        audit.record(actor, "CONNECTION_SAVED", id);
        return read(id).view();
    }
    private String origin(String value) {
        if (value.isBlank()) return "";
        var uri = java.net.URI.create(value);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }
}
