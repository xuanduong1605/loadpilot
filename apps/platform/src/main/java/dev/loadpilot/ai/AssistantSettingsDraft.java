package dev.loadpilot.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.experiments.ExperimentSettingsApi;
import dev.loadpilot.experiments.ExperimentSettingsApi.EffectiveInput;
import dev.loadpilot.experiments.ExperimentSettingsApi.EffectiveReview;
import dev.loadpilot.experiments.ExperimentSettingsApi.Settings;
import dev.loadpilot.experiments.ExecutionBounds;
import dev.loadpilot.experiments.LoadProfile;
import dev.loadpilot.experiments.SloPolicy;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AssistantSettingsDraft {
    private static final List<String> SETTINGS = List.of("minimumRate", "maximumRate", "durationSeconds", "warmupSeconds",
            "maximumErrorRatio", "repetitions", "maximumRuns", "totalBudgetSeconds");
    private static final List<String> BOUNDS = List.of("precisionIterationsPerSecond", "preAllocatedVUs", "maximumVUs",
            "generatorCpuMillicores", "generatorMemoryMiB", "requestTimeoutMs", "startupTimeoutSeconds",
            "gracefulStopSeconds", "maximumAttemptsPerRun");
    private static final List<String> OTHER = List.of("measurementSeconds", "latencyThresholdMs", "latencyPercentile");
    private static final List<String> PROFILE_FIELDS = List.of("durationSeconds", "warmupSeconds", "measurementSeconds",
            "precisionIterationsPerSecond");

    private final AssistantSuggestions suggestions;
    private final ExperimentSettingsApi settings;
    private final Validator validator;
    private final ObjectMapper json;

    AssistantSettingsDraft(AssistantSuggestions suggestions, ExperimentSettingsApi settings, Validator validator, ObjectMapper json) {
        this.suggestions = suggestions;
        this.settings = settings;
        this.validator = validator;
        this.json = json;
    }

    record Input(@NotBlank @Size(max = 2000) String goal, @Valid EffectiveInput current) {}
    record Change(String field, Number value) {}
    record Proposal(List<Change> changes, EffectiveReview preview, List<String> errors,
                    @JsonInclude(JsonInclude.Include.NON_NULL) LoadProfile loadProfile) {}
    private record Setting(String section, String property, Class<?> formType) {}
    private record ProfileDraft(LoadProfile profile, int durationSeconds, int warmupSeconds) {}

    @PostMapping("/api/assistant/settings-draft")
    Proposal draft(@Valid @RequestBody Input input, Principal operator) {
        String goal = input.goal().strip();
        if (suggestions.containsCredentials(goal) || goal.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                && cp != '\n' && cp != '\r' && cp != '\t')) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remove credentials and control characters from the goal.");
        }
        String result = suggestions.proposeSettings(operator.getName(), goal, schema()).require("Continue editing manually or try again later.");

        ObjectNode proposed = input.current() == null ? null : json.valueToTree(input.current());
        List<Change> changes = new ArrayList<>();
        List<String> fieldErrors = new ArrayList<>();
        try {
            JsonNode root = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(result);
            JsonNode entries = root.path("changes");
            JsonNode loadProfile = root.path("profile");
            if (!root.isObject() || root.size() != (loadProfile.isMissingNode() ? 1 : 2)
                    || !entries.isArray() || entries.size() > fieldCount()) throw malformed();
            var seen = new HashSet<String>();
            for (JsonNode entry : entries) {
                String field = entry.path("field").textValue();
                JsonNode value = entry.path("value");
                if (!entry.isObject() || entry.size() != 2 || field == null || !seen.add(field)
                        || !value.isNumber() || !Double.isFinite(value.doubleValue())
                        || (!field.equals("maximumErrorRatio") && (!value.isIntegralNumber() || !value.canConvertToInt()))) {
                    throw malformed();
                }
                Setting setting = setting(field);
                Object formValue = formValue(field, value);
                validator.validateValue(setting.formType(), setting.property(), formValue).forEach(error ->
                        fieldErrors.add(setting.section() + "." + setting.property() + " " + error.getMessage()));
                if (proposed != null) apply(proposed, setting, json.valueToTree(formValue));
                changes.add(new Change(field, value.numberValue()));
            }
            ProfileDraft profile = null;
            if (!loadProfile.isMissingNode()) {
                if (PROFILE_FIELDS.stream().anyMatch(seen::contains)) throw malformed();
                profile = readProfile(loadProfile, fieldErrors);
                if (profile != null) {
                    var timing = List.of(new Change("durationSeconds", profile.durationSeconds()), new Change("warmupSeconds", profile.warmupSeconds()),
                            new Change("measurementSeconds", profile.profile().measurementSeconds()));
                    changes.addAll(timing);
                    if (proposed == null && fieldErrors.isEmpty()) fieldErrors.addAll(partialProfileErrors(profile, changes));
                    if (proposed != null) {
                        for (var change : timing) apply(proposed, setting(change.field()), json.valueToTree(change.value()));
                        proposed.set("loadProfile", json.valueToTree(profile.profile()));
                        ((ObjectNode) proposed.path("executionBounds")).putNull("precisionIterationsPerSecond");
                    }
                }
            }
            List<Change> reviewedChanges = List.copyOf(changes);
            if (!fieldErrors.isEmpty()) return new Proposal(reviewedChanges, null, fieldErrors.stream().sorted().toList(), null);
            if (changes.isEmpty()) return new Proposal(List.of(), null,
                    List.of("Specify the setting, value, and unit to interpret. For a load profile, include rates, stage durations, warm-up and measurement length."), null);
            var profilePreview = profile == null ? null : profile.profile();
            if (proposed == null) return new Proposal(reviewedChanges, null, List.of(), profilePreview);
            if (profile == null && seen.contains("durationSeconds")) updateSteadyDuration(proposed, input.current().loadProfile());
            EffectiveInput candidate = json.treeToValue(proposed, EffectiveInput.class);
            var violations = validator.validate(candidate).stream()
                    .map(error -> error.getPropertyPath() + " " + error.getMessage()).sorted().toList();
            if (!violations.isEmpty()) return new Proposal(reviewedChanges, null, violations, null);
            EffectiveReview review = settings.validateEffective(candidate);
            return new Proposal(reviewedChanges, review.valid() ? review : null, review.errors(), review.valid() ? profilePreview : null);
        } catch (JsonProcessingException invalid) {
            throw malformed();
        }
    }

    private ProfileDraft readProfile(JsonNode node, List<String> errors) throws JsonProcessingException {
        if (!(node instanceof ObjectNode input) || input.size() != 5 || !input.path("shape").isTextual()
                || !input.path("startRate").isIntegralNumber() || !input.path("measurementSeconds").isIntegralNumber()
                || !input.path("warmupSeconds").isIntegralNumber() || !input.path("warmupSeconds").canConvertToInt()
                || !input.path("stages").isArray() || input.path("stages").isEmpty() || input.path("stages").size() > 12) throw malformed();
        for (var stage : input.path("stages")) {
            if (!stage.isObject() || stage.size() != 2 || !stage.path("durationSeconds").isIntegralNumber()
                    || !stage.path("targetRate").isIntegralNumber()) throw malformed();
        }
        var profileNode = input.deepCopy();
        int warmup = profileNode.remove("warmupSeconds").intValue();
        profileNode.put("mode", "SINGLE_RUN");
        var profile = json.treeToValue(profileNode, LoadProfile.class);
        var violations = validator.validate(profile);
        violations.forEach(error -> errors.add("loadProfile." + error.getPropertyPath() + " " + error.getMessage()));
        if (!violations.isEmpty()) return null;
        int duration = profile.stages().stream().mapToInt(LoadProfile.Stage::durationSeconds).sum();
        if (duration > 3600) errors.add("The load stages must fit within a 3600-second run.");
        if (warmup < 0 || warmup >= duration) errors.add("Warm-up must be nonnegative and shorter than the run duration.");
        if (duration > 3600 || warmup < 0 || warmup >= duration) return null;
        return new ProfileDraft(profile, duration, warmup);
    }

    private static List<String> partialProfileErrors(ProfileDraft draft, List<Change> changes) {
        int minimum = changes.stream().filter(change -> change.field().equals("minimumRate")).findFirst()
                .map(change -> change.value().intValue()).orElse(1);
        int maximum = changes.stream().filter(change -> change.field().equals("maximumRate")).findFirst()
                .map(change -> change.value().intValue()).orElse(10000);
        if (minimum > maximum) return List.of("Minimum rate must not exceed maximum rate.");
        var bounds = new Settings(minimum, maximum, draft.durationSeconds(), draft.warmupSeconds(), null, null, null, null, null);
        return draft.profile().errors(bounds);
    }

    private static void updateSteadyDuration(ObjectNode proposed, LoadProfile load) {
        if (load.mode() == LoadProfile.Mode.SINGLE_RUN && load.shape() == LoadProfile.Shape.STEADY && load.stages().size() == 1) {
            // The form derives the single steady stage from the experiment duration.
            var stage = (ObjectNode) proposed.path("loadProfile").path("stages").get(0);
            stage.set("durationSeconds", proposed.path("settings").path("durationSeconds"));
        }
    }

    private static Setting setting(String field) {
        if (SETTINGS.contains(field)) return new Setting("settings", field, ExperimentSettingsApi.Settings.class);
        if (BOUNDS.contains(field)) return new Setting("executionBounds", field, ExecutionBounds.class);
        return switch (field) {
            case "measurementSeconds" -> new Setting("loadProfile", field, LoadProfile.class);
            case "latencyThresholdMs", "latencyPercentile" -> new Setting("sloPolicy", field, SloPolicy.class);
            default -> throw malformed();
        };
    }

    private static Object formValue(String field, JsonNode value) {
        if (field.equals("maximumErrorRatio")) return value.doubleValue();
        if (field.equals("latencyPercentile")) return switch (value.intValue()) {
            case 90 -> SloPolicy.Percentile.P90;
            case 95 -> SloPolicy.Percentile.P95;
            case 99 -> SloPolicy.Percentile.P99;
            default -> throw malformed();
        };
        return value.intValue();
    }

    private static void apply(ObjectNode proposed, Setting setting, JsonNode value) {
        ((ObjectNode) proposed.path(setting.section())).set(setting.property(), value);
        if (setting.property().equals("maximumErrorRatio")) ((ObjectNode) proposed.path("sloPolicy")).set("maximumErrorRatio", value);
        if (setting.property().equals("latencyThresholdMs")) ((ObjectNode) proposed.path("settings")).set("p95LatencyMs", value);
    }

    private ObjectNode schema() {
        var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("changes");
        var fields = schema.putObject("properties");
        // Keep Gemini's output grammar small. The parser and experiment validators enforce the business bounds.
        var changes = fields.putObject("changes").put("type", "array");
        var item = changes.putObject("items").put("type", "object").put("additionalProperties", false);
        item.putArray("required").add("field").add("value");
        var properties = item.putObject("properties");
        var names = properties.putObject("field").put("type", "string").putArray("enum");
        for (var allowed : List.of(SETTINGS, BOUNDS, OTHER)) allowed.forEach(names::add);
        properties.putObject("value").put("type", "number").put("description", "Rates and precision are iterations/s. "
                + "Time fields use their stated units. Error ratio is 0 to 1. maximumRuns counts attempts, including retries.");
        var profile = fields.putObject("profile").put("type", "object").put("additionalProperties", false);
        profile.putArray("required").add("shape").add("startRate").add("warmupSeconds").add("measurementSeconds").add("stages");
        var profileFields = profile.putObject("properties");
        var shapes = profileFields.putObject("shape").put("type", "string").putArray("enum");
        for (var shape : LoadProfile.Shape.values()) shapes.add(shape.name());
        profileFields.putObject("startRate").put("type", "integer");
        profileFields.putObject("warmupSeconds").put("type", "integer");
        profileFields.putObject("measurementSeconds").put("type", "integer");
        var stage = profileFields.putObject("stages").put("type", "array")
                .putObject("items").put("type", "object").put("additionalProperties", false);
        stage.putArray("required").add("durationSeconds").add("targetRate");
        var stageFields = stage.putObject("properties");
        stageFields.putObject("durationSeconds").put("type", "integer");
        stageFields.putObject("targetRate").put("type", "integer");
        return schema;
    }

    private static int fieldCount() {
        return SETTINGS.size() + BOUNDS.size() + OTHER.size();
    }

    private static ResponseStatusException malformed() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned an unsupported settings proposal.");
    }
}
