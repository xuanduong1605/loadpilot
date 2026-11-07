package dev.loadpilot.experiments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/experiments/drafts")
public class ExperimentDrafts {
    public record Input(@NotNull UUID targetId, @NotNull @Valid ExperimentSettingsApi.Settings settings,
                        @Positive Long revision, UUID scenarioId, @Positive Integer scenarioVersion, @Valid ExecutionBounds executionBounds, @Valid LoadProfile loadProfile, @Valid SloPolicy sloPolicy) {}
    public record Draft(UUID id, UUID targetId, ExperimentSettingsApi.Settings settings, long revision,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt, UUID scenarioId, Integer scenarioVersion, ExecutionBounds executionBounds, LoadProfile loadProfile, SloPolicy sloPolicy) {}
    private final Store store;
    public ExperimentDrafts(Store store) { this.store = store; }
    @PostMapping public ResponseEntity<Draft> create(@Valid @RequestBody Input input, Principal actor) {
        var draft = store.save(null, input, actor.getName());
        return ResponseEntity.created(java.net.URI.create("/api/experiments/drafts/" + draft.id())).body(draft);
    }
    @GetMapping("/{id}") public Draft get(@PathVariable UUID id) { return store.get(id); }
    @PutMapping("/{id}") public Draft update(@PathVariable UUID id, @Valid @RequestBody Input input, Principal actor) {
        return store.save(id, input, actor.getName());
    }
    public static class Problem extends RuntimeException {
        final int status;
        public Problem(int status, String message) { super(message); this.status = status; }
    }
    @Service
    public static class Store {
        private final JdbcTemplate db;
        private final ObjectMapper json;
        private final ExperimentSettingsApi validation;
        private final dev.loadpilot.scenarios.ScenarioStore scenarios;
        private final dev.loadpilot.targets.TargetStore targets;
        public Store(JdbcTemplate db, ObjectMapper json, ExperimentSettingsApi validation, dev.loadpilot.scenarios.ScenarioStore scenarios, dev.loadpilot.targets.TargetStore targets) {
            this.db = db; this.json = json; this.validation = validation; this.scenarios = scenarios; this.targets = targets;
        }
        private ExperimentSettingsApi.Settings decode(String value) {
            try { return json.readValue(value, ExperimentSettingsApi.Settings.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Stored experiment settings are invalid", error); }
        }
        private ExecutionBounds bounds(String value) {
            if (value == null) return null;
            try { return json.readValue(value, ExecutionBounds.class); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored execution bounds are invalid", invalid); }
        }
        private LoadProfile profile(String value) {
            if (value == null) return null;
            try { return json.readValue(value, LoadProfile.class); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored load profile is invalid", invalid); }
        }
        private SloPolicy slo(String value) {
            if (value == null) return null;
            try { return json.readValue(value, SloPolicy.class); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored SLO policy is invalid", invalid); }
        }
        public Draft get(UUID id) {
            return db.query("SELECT * FROM experiment_draft WHERE id=?", (row, index) -> new Draft(
                    row.getObject("id", UUID.class), row.getObject("target_id", UUID.class), decode(row.getString("settings")),
                    row.getLong("revision"), row.getObject("created_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class), row.getObject("scenario_id", UUID.class), (Integer) row.getObject("scenario_version"), bounds(row.getString("execution_bounds")), profile(row.getString("load_profile")), slo(row.getString("slo_policy"))), id)
                    .stream().findFirst().orElseThrow(() -> new Problem(404, "Experiment draft not found."));
        }
        @Transactional public Draft save(UUID id, Input input, String actor) {
            var review = validation.validate(input.settings());
            if (!review.valid()) throw new Problem(400, String.join(" ", review.errors()));
            if (input.executionBounds() != null && !input.executionBounds().errors(input.settings(), input.loadProfile()).isEmpty())
                throw new Problem(400, String.join(" ", input.executionBounds().errors(input.settings(), input.loadProfile())));
            if (input.loadProfile() != null && !input.loadProfile().errors(input.settings()).isEmpty()) throw new Problem(400, String.join(" ", input.loadProfile().errors(input.settings())));
            if (input.sloPolicy() != null && !input.sloPolicy().errors(input.settings()).isEmpty()) throw new Problem(400, String.join(" ", input.sloPolicy().errors(input.settings())));
            if (targets.forReview(input.targetId()).archivedAt() != null) throw new Problem(400, "Choose an active registered system.");
            if ((input.scenarioId() == null) != (input.scenarioVersion() == null)) throw new Problem(400, "Choose both a scenario and an exact version.");
            if (input.scenarioId() != null) scenarios.version(input.scenarioId(), input.scenarioVersion());
            String settings;
            String bounds = null;
            String profile = null;
            String slo = null;
            try { settings = json.writeValueAsString(input.settings()); }
            catch (JsonProcessingException error) { throw new IllegalStateException(error); }
            if (input.sloPolicy() != null) try { slo = json.writeValueAsString(input.sloPolicy()); }
            catch (JsonProcessingException error) { throw new IllegalStateException(error); }
            if (input.loadProfile() != null) try { profile = json.writeValueAsString(input.loadProfile()); }
            catch (JsonProcessingException error) { throw new IllegalStateException(error); }
            if (input.executionBounds() != null) try { bounds = json.writeValueAsString(input.executionBounds()); }
            catch (JsonProcessingException error) { throw new IllegalStateException(error); }
            if (id == null) {
                id = UUID.randomUUID();
                db.update("INSERT INTO experiment_draft(id,target_id,settings,scenario_id,scenario_version,execution_bounds,load_profile,slo_policy) VALUES (?,?,?::jsonb,?,?,?::jsonb,?::jsonb,?::jsonb)", id, input.targetId(), settings, input.scenarioId(), input.scenarioVersion(), bounds, profile, slo);
            } else {
                if (input.revision() == null) throw new Problem(400, "Provide the draft revision.");
                if (db.update("UPDATE experiment_draft SET target_id=?,settings=?::jsonb,scenario_id=?,scenario_version=?,execution_bounds=?::jsonb,load_profile=?::jsonb,slo_policy=?::jsonb,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=?",
                        input.targetId(), settings, input.scenarioId(), input.scenarioVersion(), bounds, profile, slo, id, input.revision()) != 1) {
                    get(id);
                    throw new Problem(409, "This draft changed. Reload it before saving again.");
                }
            }
            db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES (?,'EXPERIMENT_DRAFT_SAVED','EXPERIMENT',?,'SUCCESS')", actor, id);
            return get(id);
        }
    }
}
