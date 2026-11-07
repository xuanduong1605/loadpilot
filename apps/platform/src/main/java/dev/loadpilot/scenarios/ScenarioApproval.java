package dev.loadpilot.scenarios;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scenarios/{id}/versions/{version}/approval")
public class ScenarioApproval {
    public record Input(@NotNull UUID validationId, @NotNull @Pattern(regexp="[0-9a-f]{64}") String sha256,
                        @NotNull @AssertTrue Boolean acknowledged) {}
    public record Approval(UUID id, UUID scenarioId, int version, String sha256, UUID validationId,
                           String approvedBy, OffsetDateTime approvedAt, boolean runtimeCheckRequired) {}
    private final Store store;
    public ScenarioApproval(Store store) { this.store = store; }
    @PostMapping public Approval approve(@PathVariable UUID id, @PathVariable int version, @Valid @RequestBody Input input, Principal actor) {
        return store.approve(id, version, input, actor.getName());
    }
    @Service
    public static class Store {
        private final JdbcTemplate db;
        public Store(JdbcTemplate db) { this.db = db; }
        @Transactional public Approval approve(UUID scenarioId, int version, Input input, String actor) {
            if (!Boolean.TRUE.equals(input.acknowledged())) throw new ScenarioStore.Problem(400, "Explicit saved-source review is required.");
            // Lock only the parent identity to serialize retries; source/version rows never change.
            if (db.query("SELECT id FROM scenario WHERE id=? FOR UPDATE", (row, index) -> row.getObject(1, UUID.class), scenarioId).isEmpty())
                throw new ScenarioStore.Problem(404, "Scenario not found.");
            int matches = db.queryForObject("SELECT count(*) FROM scenario_validation c JOIN scenario_version v ON v.scenario_id=c.scenario_id AND v.version=c.version WHERE c.id=? AND c.scenario_id=? AND c.version=? AND c.sha256=? AND v.sha256=c.sha256 AND c.syntax_valid AND c.policy_accepted AND c.policy_version='static-review-v1' AND c.parser_version='8.19.0'",
                    Integer.class, input.validationId(), scenarioId, version, input.sha256());
            if (matches != 1) throw new ScenarioStore.Problem(409, "Approval requires passing current static checks for this exact saved version and hash.");
            int inserted = db.update("INSERT INTO scenario_approval(id,scenario_id,version,sha256,validation_id,policy_version,parser_version,approved_by) VALUES (?,?,?,?,?,'static-review-v1','8.19.0',?) ON CONFLICT (scenario_id,version,sha256,policy_version,parser_version) DO NOTHING",
                    UUID.randomUUID(), scenarioId, version, input.sha256(), input.validationId(), actor);
            if (inserted == 1) db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES (?,'SCENARIO_APPROVED','SCENARIO',?,'SUCCESS')", actor, scenarioId);
            return db.queryForObject("SELECT * FROM scenario_approval WHERE scenario_id=? AND version=? AND sha256=? AND policy_version='static-review-v1' AND parser_version='8.19.0'",
                    (row, index) -> new Approval(row.getObject("id", UUID.class), scenarioId, version, input.sha256(), row.getObject("validation_id", UUID.class), row.getString("approved_by"), row.getObject("approved_at", OffsetDateTime.class), true), scenarioId, version, input.sha256());
        }
    }
}
