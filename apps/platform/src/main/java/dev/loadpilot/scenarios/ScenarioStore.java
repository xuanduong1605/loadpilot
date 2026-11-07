package dev.loadpilot.scenarios;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioStore {
    public static class Problem extends RuntimeException {
        final int status;
        public Problem(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
    private final JdbcTemplate db;
    public ScenarioStore(JdbcTemplate db) { this.db = db; }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ScenarioApi.Page list(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new Problem(400, "Check scenario pagination.");
        return new ScenarioApi.Page(db.query("SELECT * FROM scenario ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                (row, index) -> new ScenarioApi.Summary(row.getObject("id", UUID.class), row.getString("name"), row.getInt("latest_version"), row.getObject("created_at", OffsetDateTime.class)), limit, offset),
                db.queryForObject("SELECT count(*) FROM scenario", Long.class), offset, limit);
    }
    public ScenarioApi.Version latest(UUID id) {
        var versions = db.query("SELECT latest_version FROM scenario WHERE id=?", (row, index) -> row.getInt(1), id);
        if (versions.isEmpty()) throw new Problem(404, "Scenario not found.");
        return version(id, versions.getFirst());
    }
    public ScenarioApi.Version version(UUID id, int version) {
        return db.query("SELECT v.*,s.name,EXISTS(SELECT 1 FROM scenario_approval a WHERE a.scenario_id=v.scenario_id AND a.version=v.version AND a.sha256=v.sha256 AND a.policy_version='static-review-v1' AND a.parser_version='8.19.0') AS approved FROM scenario_version v JOIN scenario s ON s.id=v.scenario_id WHERE v.scenario_id=? AND v.version=?",
                (row, index) -> new ScenarioApi.Version(id, row.getString("name"), version, row.getString("source"), row.getString("sha256"), row.getString("created_by"), row.getObject("created_at", OffsetDateTime.class), row.getBoolean("approved")), id, version)
                .stream().findFirst().orElseThrow(() -> new Problem(404, "Scenario version not found."));
    }
    public record ApprovedVersion(ScenarioApi.Version script, UUID approvalId, UUID validationId, String policyVersion,
                                  String parserVersion, String approvedBy, OffsetDateTime approvedAt) {}
    public ApprovedVersion forReview(UUID id, int version) {
        var script = version(id, version);
        if (!script.approved()) throw new Problem(409, "Approve the exact saved script version before reviewing experiment inputs.");
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.source().getBytes(StandardCharsets.UTF_8)));
            if (!digest.equals(script.sha256())) throw new Problem(409, "Saved script hash verification failed.");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return db.query("SELECT * FROM scenario_approval WHERE scenario_id=? AND version=? AND sha256=? AND policy_version='static-review-v1' AND parser_version='8.19.0'",
                (row, index) -> new ApprovedVersion(script, row.getObject("id", UUID.class), row.getObject("validation_id", UUID.class), row.getString("policy_version"), row.getString("parser_version"), row.getString("approved_by"), row.getObject("approved_at", OffsetDateTime.class)), id, version, script.sha256())
                .stream().findFirst().orElseThrow(() -> new Problem(409, "Current script approval is unavailable."));
    }
    @Transactional public ScenarioApi.Version save(UUID id, ScenarioApi.Input input, String actor) {
        if (input.source().indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(input.source()))
            throw new Problem(400, "Script source must be valid UTF-8 text without null characters.");
        byte[] bytes = input.source().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) throw new Problem(400, "Script source exceeds the 64 KiB UTF-8 limit.");
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        int version = 1;
        if (id == null) {
            if (input.expectedVersion() != null) throw new Problem(400, "New scenarios must not specify an expected version.");
            id = UUID.randomUUID();
            db.update("INSERT INTO scenario(id,name) VALUES (?,?)", id, input.name().trim());
        } else {
            var current = db.query("SELECT latest_version,name FROM scenario WHERE id=? FOR UPDATE", (row, index) -> new Object[]{row.getInt(1), row.getString(2)}, id);
            if (current.isEmpty()) throw new Problem(404, "Scenario not found.");
            int previous = (Integer) current.getFirst()[0];
            if (input.expectedVersion() == null || input.expectedVersion() != previous) throw new Problem(409, "A newer version exists. Reopen the scenario before saving your edits.");
            if (!input.name().trim().equals(current.getFirst()[1])) throw new Problem(400, "Scenario name cannot change when saving a version.");
            version = Math.addExact(previous, 1);
            db.update("UPDATE scenario SET latest_version=? WHERE id=?", version, id);
        }
        db.update("INSERT INTO scenario_version(scenario_id,version,source,sha256,created_by) VALUES (?,?,?,?,?)", id, version, input.source(), hash, actor);
        db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES (?,'SCENARIO_VERSION_SAVED','SCENARIO',?,'SUCCESS')", actor, id);
        return version(id, version);
    }
}
