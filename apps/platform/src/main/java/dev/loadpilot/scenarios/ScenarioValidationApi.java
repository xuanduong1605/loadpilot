package dev.loadpilot.scenarios;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scenarios/{id}/versions/{version}/validation")
public class ScenarioValidationApi {
    public record Input(@NotNull @Pattern(regexp="[0-9a-f]{64}") String sha256) {}
    public record Review(UUID id, UUID scenarioId, int version, String sha256, boolean syntaxValid, boolean policyAccepted,
                         String policyVersion, String parserVersion, List<ScriptParser.Finding> findings,
                         String createdBy, OffsetDateTime createdAt, boolean runtimeCheckRequired) {}
    private final ScenarioStore scenarios;
    private final ScriptParser parser;
    private final Store reviews;
    public ScenarioValidationApi(ScenarioStore scenarios, ScriptParser parser, Store reviews) { this.scenarios = scenarios; this.parser = parser; this.reviews = reviews; }
    @PostMapping public Review validate(@PathVariable UUID id, @PathVariable int version, @Valid @RequestBody Input input, Principal actor) {
        var saved = scenarios.version(id, version);
        if (!saved.sha256().equals(input.sha256())) throw new ScenarioStore.Problem(409, "The reviewed script hash does not match this saved version.");
        return reviews.save(saved, parser.parse(saved.source()), actor.getName());
    }
    @GetMapping public Review latest(@PathVariable UUID id, @PathVariable int version) { scenarios.version(id, version); return reviews.latest(id, version); }
    @Service
    public static class Store {
        private final JdbcTemplate db;
        private final ObjectMapper json;
        public Store(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
        public Review latest(UUID id, int version) {
            return db.query("SELECT * FROM scenario_validation WHERE scenario_id=? AND version=? ORDER BY created_at DESC,id DESC LIMIT 1", (row, index) -> {
                List<ScriptParser.Finding> findings;
                try { findings = json.readValue(row.getString("findings"), new TypeReference<List<ScriptParser.Finding>>() {}); }
                catch (JsonProcessingException failed) { throw new IllegalStateException("Invalid stored validation", failed); }
                return new Review(row.getObject("id", UUID.class), id, version, row.getString("sha256"), row.getBoolean("syntax_valid"), row.getBoolean("policy_accepted"),
                        row.getString("policy_version"), row.getString("parser_version"), findings, row.getString("created_by"), row.getObject("created_at", OffsetDateTime.class), true);
            }, id, version).stream().findFirst().orElseThrow(() -> new ScenarioStore.Problem(404, "This version has no saved validation."));
        }
        @Transactional public Review save(ScenarioApi.Version saved, ScriptParser.Result result, String actor) {
            UUID id = UUID.randomUUID();
            String findings;
            try { findings = json.writeValueAsString(result.findings()); }
            catch (JsonProcessingException failed) { throw new IllegalStateException(failed); }
            db.update("INSERT INTO scenario_validation(id,scenario_id,version,sha256,syntax_valid,policy_accepted,policy_version,parser_version,findings,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,clock_timestamp())",
                    id, saved.scenarioId(), saved.version(), saved.sha256(), result.syntaxValid(), result.policyAccepted(), result.policyVersion(), result.parserVersion(), findings, actor);
            db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES (?,'SCENARIO_CHECKED','SCENARIO',?,'SUCCESS')", actor, saved.scenarioId());
            return new Review(id, saved.scenarioId(), saved.version(), saved.sha256(), result.syntaxValid(), result.policyAccepted(), result.policyVersion(), result.parserVersion(), result.findings(), actor, db.queryForObject("SELECT created_at FROM scenario_validation WHERE id=?", OffsetDateTime.class, id), true);
        }
    }
}
