package dev.loadpilot.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.experiments.ExperimentSettingsApi;
import dev.loadpilot.experiments.ExperimentSettingsApi.EffectiveInput;
import dev.loadpilot.experiments.ExperimentSettingsApi.EffectiveReview;
import dev.loadpilot.experiments.ExecutionBounds;
import dev.loadpilot.experiments.LoadProfile;
import dev.loadpilot.experiments.SloPolicy;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    record Proposal(List<Change> changes, EffectiveReview preview, List<String> errors) {}
    private record Setting(String section, String property, Class<?> formType) {}

    @PostMapping("/api/assistant/settings-draft")
    Proposal draft(@Valid @RequestBody Input input) {
        String goal = input.goal().strip();
        if (suggestions.containsCredentials(goal) || goal.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                && cp != '\n' && cp != '\r' && cp != '\t')) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remove credentials and control characters from the goal.");
        }
        String result = suggestions.proposeSettings(goal, schema());
        if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI settings suggestions are unavailable. Continue editing manually or try again later.");

        ObjectNode proposed = input.current() == null ? null : json.valueToTree(input.current());
        List<Change> changes = new ArrayList<>();
        List<String> fieldErrors = new ArrayList<>();
        try {
            JsonNode root = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(result);
            JsonNode entries = root.path("changes");
            if (!root.isObject() || root.size() != 1 || !entries.isArray() || entries.size() > fieldCount()) throw malformed();
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
            if (changes.isEmpty()) return new Proposal(List.of(), null, List.of("Specify the setting, value, and unit to interpret."));

            List<Change> reviewedChanges = List.copyOf(changes);
            if (!fieldErrors.isEmpty()) return new Proposal(reviewedChanges, null, fieldErrors.stream().sorted().toList());
            if (proposed == null) return new Proposal(reviewedChanges, null, List.of());
            EffectiveInput candidate = json.treeToValue(proposed, EffectiveInput.class);
            var violations = validator.validate(candidate).stream()
                    .map(error -> error.getPropertyPath() + " " + error.getMessage()).sorted().toList();
            if (!violations.isEmpty()) return new Proposal(reviewedChanges, null, violations);
            EffectiveReview review = settings.validateEffective(candidate);
            return new Proposal(reviewedChanges, review.valid() ? review : null, review.errors());
        } catch (JsonProcessingException invalid) {
            throw malformed();
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
        var changes = schema.putObject("properties").putObject("changes").put("type", "array").put("maxItems", fieldCount());
        var item = changes.putObject("items").put("type", "object").put("additionalProperties", false);
        item.putArray("required").add("field").add("value");
        var properties = item.putObject("properties");
        var names = properties.putObject("field").put("type", "string").putArray("enum");
        for (var fields : List.of(SETTINGS, BOUNDS, OTHER)) fields.forEach(names::add);
        properties.putObject("value").put("type", "number").put("description", "Rates and precision are iterations/s. "
                + "Time fields use their stated units. Error ratio is 0 to 1. maximumRuns counts attempts, including retries.");
        return schema;
    }

    private static int fieldCount() {
        return SETTINGS.size() + BOUNDS.size() + OTHER.size();
    }

    private static ResponseStatusException malformed() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned an unsupported settings proposal.");
    }
}
