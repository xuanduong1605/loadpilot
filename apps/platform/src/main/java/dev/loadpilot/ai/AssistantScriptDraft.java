package dev.loadpilot.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.experiments.StrictInteger;
import dev.loadpilot.scenarios.ScriptParser;
import dev.loadpilot.targets.Target;
import dev.loadpilot.targets.TargetProblem;
import dev.loadpilot.targets.TargetStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AssistantScriptDraft {
    private final ObjectMapper json;
    private final AssistantSuggestions suggestions;
    private final TargetStore targets;
    private final ScriptParser parser;

    AssistantScriptDraft(ObjectMapper json, AssistantSuggestions suggestions, TargetStore targets, ScriptParser parser) {
        this.json = json;
        this.suggestions = suggestions;
        this.targets = targets;
        this.parser = parser;
    }

    record Input(@NotBlank @Size(max = 2000) String goal, @NotNull UUID targetId,
                 @NotNull @Min(1) @Max(120000) @JsonDeserialize(using = StrictInteger.class)
                 Integer requestTimeoutMs) {}
    record Draft(String source, String method, Integer expectedStatus, ScriptParser.Result review,
                 ScriptParser.Result executionReview, List<String> errors) {}

    @PostMapping("/api/assistant/script-draft")
    Draft draft(@Valid @RequestBody Input input, Principal operator) {
        String goal = input.goal().strip();
        String lowerGoal = goal.toLowerCase(Locale.ROOT);
        if (suggestions.containsCredentials(goal) || lowerGoal.contains("http://") || lowerGoal.contains("https://")
                || goal.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the system separately and remove URLs, credentials, and control characters from the goal.");
        }
        Target target;
        try {
            target = targets.get(input.targetId());
        } catch (TargetProblem problem) {
            throw new ResponseStatusException(HttpStatus.valueOf(problem.status()), problem.getMessage());
        }
        if (target.archivedAt() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a system that is not archived.");

        String response = suggestions.proposeScript(operator.getName(), goal, schema()).require("Continue editing manually or try again later.");
        try {
            JsonNode proposal = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(response);
            String method = proposal.path("method").textValue();
            JsonNode status = proposal.path("expectedStatus");
            if (!proposal.isObject() || proposal.size() != 2 || method == null
                    || (!status.isNull() && (!status.isIntegralNumber() || !status.canConvertToInt()
                    || status.intValue() < 200 || status.intValue() > 599))) throw malformed();
            if (method.equals("UNSUPPORTED")) return new Draft(null, null, null, null, null, List.of(
                    "Describe one GET or HEAD request to the selected system and, optionally, its expected HTTP status."));
            if (!method.equals("GET") && !method.equals("HEAD")) throw malformed();
            Integer expected = status.isNull() ? null : status.intValue();
            String condition = expected == null ? "r.status >= 200 && r.status < 400" : "r.status === " + expected;
            String source = """
                    import http from 'k6/http';
                    import { check } from 'k6';

                    export default function () {
                      const response = http.%s(%s, { timeout: %d, redirects: 0 });
                      check(response, { 'expected HTTP status': (r) => %s });
                    }
                    """.formatted(method.toLowerCase(Locale.ROOT), json.writeValueAsString(target.baseUrl()), input.requestTimeoutMs(), condition);
            var review = parser.parse(source);
            if (!review.policyAccepted()) return new Draft(null, method, expected, review, null, List.of("The script did not pass static review."));
            var execution = parser.parseExecution(source, target.baseUrl(), input.requestTimeoutMs());
            return execution.policyAccepted() ? new Draft(source, method, expected, review, execution, List.of())
                    : new Draft(null, method, expected, review, execution, List.of("The script did not pass the literal HTTP policy."));
        } catch (JsonProcessingException invalid) {
            throw malformed();
        }
    }

    private ObjectNode schema() {
        var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("method").add("expectedStatus");
        var properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").putArray("enum").add("GET").add("HEAD").add("UNSUPPORTED");
        var status = properties.putObject("expectedStatus").put("minimum", 200).put("maximum", 599);
        status.putArray("type").add("integer").add("null");
        return schema;
    }

    private static ResponseStatusException malformed() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned an unsupported script proposal.");
    }
}
