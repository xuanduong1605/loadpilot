package dev.loadpilot.experiments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import dev.loadpilot.scenarios.ScenarioStore;
import dev.loadpilot.targets.TargetStore;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentSnapshots {
    public record TargetInput(UUID id, String name, String baseUrl, String environment, String namespace,
                              String deployment, String container, long revision) {}
    public record Inputs(String schemaVersion, UUID draftId, long draftRevision, String mode, TargetInput target,
                         ScenarioStore.ApprovedVersion scenario, ExperimentSettingsApi.Settings settings,
                         String loadUnit, String executor, int measurementSeconds, String sloOperator,
                         @JsonInclude(JsonInclude.Include.NON_NULL) ExecutionBounds executionBounds,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long maximumAttemptSeconds,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer reservedAttemptsPerLoad,
                         @JsonInclude(JsonInclude.Include.NON_NULL) LoadProfile loadProfile,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer measurementRate,
                         @JsonInclude(JsonInclude.Include.NON_NULL) SloPolicy sloPolicy) {}
    public record Preview(Inputs inputs, String sha256) {}
    public record Finalize(@NotNull @Positive Long revision, @NotNull @Pattern(regexp="[0-9a-f]{64}") String reviewHash,
                           @NotNull @AssertTrue Boolean acknowledged) {}
    public record Snapshot(UUID id, Inputs inputs, String sha256, String createdBy, OffsetDateTime createdAt,
                           boolean runtimeCheckRequired, boolean executionAvailable) {}
    private final Store store;
    public ExperimentSnapshots(Store store) { this.store = store; }
    @GetMapping("/drafts/{id}/review") public Preview preview(@PathVariable UUID id) { return store.preview(id); }
    @PostMapping("/drafts/{id}/finalize") public Snapshot finalizeInputs(@PathVariable UUID id, @Valid @RequestBody Finalize input, java.security.Principal actor) {
        return store.finalizeInputs(id, input, actor.getName());
    }
    @GetMapping("/snapshots/{id}") public Snapshot snapshot(@PathVariable UUID id) { return store.get(id); }

    @Service
    public static class Store {
        private final JdbcTemplate db;
        private final ExperimentDrafts.Store drafts;
        private final TargetStore targets;
        private final ScenarioStore scenarios;
        private final ExperimentSettingsApi settings;
        private final Validator validator;
        private final ObjectMapper canonical;
        public Store(JdbcTemplate db, ExperimentDrafts.Store drafts, TargetStore targets, ScenarioStore scenarios,
                     ExperimentSettingsApi settings, Validator validator, ObjectMapper json) {
            this.db = db; this.drafts = drafts; this.targets = targets; this.scenarios = scenarios;
            this.settings = settings; this.validator = validator;
            canonical = json.copy().setConfig(json.getSerializationConfig().with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY))
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        }
        private String encode(Inputs value) {
            try { return canonical.writeValueAsString(value); }
            catch (JsonProcessingException failed) { throw new IllegalStateException("Cannot encode experiment inputs", failed); }
        }
        private String hash(String value) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        private void lock(UUID id, boolean exclusive) {
            if (db.query("SELECT id FROM experiment_draft WHERE id=? FOR " + (exclusive ? "UPDATE" : "SHARE"), (row, index) -> row.getObject(1, UUID.class), id).isEmpty())
                throw new ExperimentDrafts.Problem(404, "Experiment draft not found.");
        }
        @Transactional public Preview preview(UUID id) {
            lock(id, false);
            var draft = drafts.get(id);
            if (draft.scenarioId() == null || draft.scenarioVersion() == null) throw new ExperimentDrafts.Problem(400, "Save a scenario and exact script version in this draft before reviewing it.");
            if (!validator.validate(draft.settings()).isEmpty() || !settings.validate(draft.settings()).valid()) throw new ExperimentDrafts.Problem(400, "Draft settings no longer satisfy current validation rules.");
            var bounds = draft.executionBounds();
            if (bounds == null || !validator.validate(bounds).isEmpty()) throw new ExperimentDrafts.Problem(400, "Complete and save explicit generator, precision and execution limits before reviewing inputs.");
            var profile = draft.loadProfile();
            if (profile == null || !validator.validate(profile).isEmpty()) throw new ExperimentDrafts.Problem(400, "Complete and save an explicit load profile before reviewing inputs.");
            var errors = new java.util.ArrayList<>(bounds.errors(draft.settings(), profile)); errors.addAll(profile.errors(draft.settings()));
            var slo = draft.sloPolicy();
            if (slo == null || !validator.validate(slo).isEmpty()) throw new ExperimentDrafts.Problem(400, "Complete and save an explicit SLO policy before reviewing inputs.");
            if (slo.resourceConditions() == null) throw new ExperimentDrafts.Problem(400, "Explicitly choose resource conditions or no resource SLO before reviewing inputs.");
            errors.addAll(slo.errors(draft.settings()));
            if (!errors.isEmpty()) throw new ExperimentDrafts.Problem(400, String.join(" ", errors));
            var target = targets.forReview(draft.targetId());
            if (target.archivedAt() != null) throw new ExperimentDrafts.Problem(409, "This system was archived. Choose an active system and save the draft again.");
            var scenario = scenarios.forReview(draft.scenarioId(), draft.scenarioVersion());
            var input = new Inputs("experiment-input-v5", id, draft.revision(), profile.mode().name(),
                    new TargetInput(target.id(), target.name(), target.baseUrl(), target.environment(), target.namespace(), target.deployment(), target.container(), target.revision()),
                    scenario, draft.settings(), "iterations/s", profile.executor(), profile.measurementSeconds(), "<=",
                    bounds, bounds.maximumAttemptSeconds(draft.settings()), bounds.reservedAttemptsPerLoad(draft.settings()), profile, profile.measurementRate(draft.settings()), slo);
            return new Preview(input, hash(encode(input)));
        }
        private RowMapper<Snapshot> mapper() {
            return (row, index) -> {
                String encoded = row.getString("inputs_json");
                if (!hash(encoded).equals(row.getString("sha256"))) throw new ExperimentDrafts.Problem(503, "Stored snapshot integrity verification failed.");
                Inputs inputs;
                try { inputs = canonical.readValue(encoded, Inputs.class); }
                catch (JsonProcessingException failed) { throw new IllegalStateException("Cannot read experiment inputs", failed); }
                return new Snapshot(row.getObject("id", UUID.class), inputs, row.getString("sha256"), row.getString("created_by"), row.getObject("created_at", OffsetDateTime.class), true, false);
            };
        }
        public Snapshot get(UUID id) {
            return db.query("SELECT * FROM experiment_input_snapshot WHERE id=?", mapper(), id).stream().findFirst()
                    .orElseThrow(() -> new ExperimentDrafts.Problem(404, "Experiment input snapshot not found."));
        }
        @Transactional(readOnly=true) public String canonicalInputs(UUID id){
            return db.query("SELECT inputs_json,sha256 FROM experiment_input_snapshot WHERE id=?",(row,index)->{String text=row.getString("inputs_json");if(!hash(text).equals(row.getString("sha256")))throw new ExperimentDrafts.Problem(503,"Stored snapshot integrity verification failed.");return text;},id).stream().findFirst().orElseThrow(()->new ExperimentDrafts.Problem(404,"Experiment input snapshot not found."));
        }
        @Transactional public Snapshot finalizeInputs(UUID id, Finalize request, String actor) {
            if (!Boolean.TRUE.equals(request.acknowledged())) throw new ExperimentDrafts.Problem(400, "Explicit input review is required.");
            lock(id, true);
            var existing = db.query("SELECT * FROM experiment_input_snapshot WHERE draft_id=? AND draft_revision=?", mapper(), id, request.revision());
            if (!existing.isEmpty()) {
                if (!existing.getFirst().sha256().equals(request.reviewHash())) throw new ExperimentDrafts.Problem(409, "This draft revision already has a different input snapshot. Save a new draft revision before finalizing changed inputs.");
                return existing.getFirst();
            }
            var review = preview(id);
            if (review.inputs().draftRevision() != request.revision() || !review.sha256().equals(request.reviewHash()))
                throw new ExperimentDrafts.Problem(409, "Draft, system or script approval changed. Reload and review the effective inputs again.");
            UUID snapshotId = UUID.randomUUID();
            db.update("INSERT INTO experiment_input_snapshot(id,draft_id,draft_revision,sha256,inputs_json,created_by) VALUES (?,?,?,?,?,?)",
                    snapshotId, id, request.revision(), review.sha256(), encode(review.inputs()), actor);
            db.update("INSERT INTO access_audit(actor,action,object_type,object_id,outcome) VALUES (?,'EXPERIMENT_INPUTS_FINALIZED','EXPERIMENT',?,'SUCCESS')", actor, snapshotId);
            return get(snapshotId);
        }
    }
}
