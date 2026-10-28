package dev.loadpilot.targets;

import dev.loadpilot.audit.TargetAudit;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TargetStore {
    private final JdbcTemplate database;
    private final TargetAudit audit;
    private final RowMapper<Target> mapper = (row, index) -> new Target(row.getObject("id", UUID.class), row.getString("name"),
            row.getString("base_url"), row.getString("environment"), row.getString("namespace"), row.getString("deployment"),
            row.getString("container"), row.getString("credential_ref"), row.getString("monitoring_ref"), row.getLong("revision"),
            row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getObject("updated_at", OffsetDateTime.class).toInstant(),
            row.getObject("archived_at", OffsetDateTime.class) == null ? null : row.getObject("archived_at", OffsetDateTime.class).toInstant());

    public TargetStore(JdbcTemplate database, TargetAudit audit) { this.database = database; this.audit = audit; }
    public record Page(List<Target> items, long total, int offset, int limit) {}

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(int offset, int limit, boolean archived) {
        if (offset < 0 || limit < 1 || limit > 100) throw TargetProblem.invalid("pagination");
        String where = archived ? "" : " WHERE archived_at IS NULL";
        return new Page(database.query("SELECT * FROM registered_target" + where + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                mapper, limit, offset), database.queryForObject("SELECT count(*) FROM registered_target" + where, Long.class), offset, limit);
    }

    public Target get(UUID id) {
        return database.query("SELECT * FROM registered_target WHERE id=?", mapper, id).stream().findFirst()
                .orElseThrow(() -> new TargetProblem(404, "TARGET_NOT_FOUND", "System not found."));
    }

    private void validate(TargetInput input) {
        try {
            URI url = URI.create(input.baseUrl());
            if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null
                    || url.getUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                    || url.getPort() == 0 || url.getPort() > 65535) throw TargetProblem.invalid("baseUrl");
        } catch (IllegalArgumentException invalid) { throw TargetProblem.invalid("baseUrl"); }
        for (String label : input.deployment().split("\\.")) if (label.length() > 63) throw TargetProblem.invalid("deployment");
    }

    private String reference(String value) { return value == null || value.isEmpty() ? null : value; }

    @Transactional
    public Target create(TargetInput input, String actor) {
        validate(input);
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO registered_target(id,name,base_url,environment,namespace,deployment,container,credential_ref,monitoring_ref) VALUES (?,?,?,?,?,?,?,?,?)",
                id, input.name().trim(), input.baseUrl(), input.environment(), input.namespace(), input.deployment(), input.container(),
                reference(input.credentialRef()), reference(input.monitoringRef()));
        audit.record(actor, "TARGET_CREATED", id);
        return get(id);
    }

    @Transactional
    public Target update(UUID id, TargetInput input, String actor) {
        validate(input);
        if (input.revision() == null) throw TargetProblem.invalid("revision");
        Target previous = get(id);
        if (id.toString().equals(previous.credentialRef()) && (!java.util.Objects.equals(reference(input.credentialRef()), previous.credentialRef())
                || !java.util.Objects.equals(reference(input.monitoringRef()), previous.monitoringRef())))
            throw TargetProblem.invalid("connection references (use connection settings)");
        int updated = database.update("UPDATE registered_target SET name=?,base_url=?,environment=?,namespace=?,deployment=?,container=?,credential_ref=?,monitoring_ref=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=? AND archived_at IS NULL",
                input.name().trim(), input.baseUrl(), input.environment(), input.namespace(), input.deployment(), input.container(),
                reference(input.credentialRef()), reference(input.monitoringRef()), id, input.revision());
        if (updated == 0) { get(id); throw TargetProblem.conflict(); }
        audit.record(actor, "TARGET_UPDATED", id);
        return get(id);
    }

    @Transactional
    public Target archive(UUID id, long revision, String actor) {
        if (revision < 1) throw TargetProblem.invalid("revision");
        Target previous = get(id);
        if (previous.archivedAt() != null) return previous;
        int updated = database.update("UPDATE registered_target SET archived_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,revision=revision+1 WHERE id=? AND revision=? AND archived_at IS NULL", id, revision);
        if (updated == 0) throw TargetProblem.conflict();
        audit.record(actor, "TARGET_ARCHIVED", id);
        return get(id);
    }

    @Transactional
    public void bindConnection(UUID id, long revision, boolean monitoring) {
        if (database.update("UPDATE registered_target SET credential_ref=?,monitoring_ref=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=? AND archived_at IS NULL",
                id.toString(), monitoring ? id.toString() : null, id, revision) == 0) throw TargetProblem.conflict();
    }
}
