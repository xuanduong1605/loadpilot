package dev.loadpilot.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Participates in the caller's transaction; no submitted connection payloads are retained. */
@Component
public class TargetAudit {
    private final JdbcTemplate database;
    public TargetAudit(JdbcTemplate database) { this.database = database; }
    public void record(String actor, String action, UUID target) {
        database.update("INSERT INTO access_audit(actor, action, object_type, object_id, outcome) VALUES (?, ?, 'TARGET', ?, 'SUCCESS')",
                actor, action, target);
    }
}
