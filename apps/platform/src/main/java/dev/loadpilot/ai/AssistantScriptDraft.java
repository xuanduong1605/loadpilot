package dev.loadpilot.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
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
                 Integer requestTimeoutMs,
                 @Size(min = 1, max = 4) List<@NotBlank @Size(max = 512) String> paths) {}
    record Step(String path, String method, Integer expectedStatus) {}
    record Draft(String source, String method, Integer expectedStatus, ScriptParser.Result review,
                 ScriptParser.Result executionReview, List<String> errors,
                 @JsonInclude(JsonInclude.Include.NON_NULL) List<Step> steps) {}
    private record Check(String method, Integer expectedStatus) {}

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

        var paths = input.paths() == null ? List.<String>of() : input.paths();
        URI base = URI.create(target.baseUrl());
        List<String> urls = paths.isEmpty() ? List.of(target.baseUrl()) : paths.stream().map(path -> resolvePath(base, path)).toList();
        String response = suggestions.proposeScript(operator.getName(), goal, schema(paths.size())).require("Continue editing manually or try again later.");
        try {
            JsonNode proposal = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(response);
            List<Check> checks = checks(proposal, paths.size());
            if (checks.isEmpty()) return new Draft(null, null, null, null, null, List.of(paths.isEmpty()
                    ? "Describe one GET or HEAD request to the selected system and its optional expected HTTP status."
                    : "Describe a GET or HEAD check for each path in order, with optional exact HTTP statuses."), null);
            List<Step> steps = paths.isEmpty() ? null : new ArrayList<>();
            if (steps != null) {
                for (int index = 0; index < paths.size(); index++) {
                    var check = checks.get(index);
                    steps.add(new Step(paths.get(index), check.method(), check.expectedStatus()));
                }
                steps = List.copyOf(steps);
            }
            String method = paths.isEmpty() ? checks.getFirst().method() : null;
            Integer expected = paths.isEmpty() ? checks.getFirst().expectedStatus() : null;
            String source = render(checks, urls, input.requestTimeoutMs());
            var review = parser.parse(source);
            if (!review.policyAccepted()) return new Draft(null, method, expected, review, null, List.of("The script did not pass static review."), steps);
            var execution = parser.parseExecution(source, target.baseUrl(), input.requestTimeoutMs());
            return execution.policyAccepted() ? new Draft(source, method, expected, review, execution, List.of(), steps)
                    : new Draft(null, method, expected, review, execution, List.of("The script did not pass the literal HTTP policy."), steps);
        } catch (JsonProcessingException invalid) {
            throw malformed();
        }
    }

    private static String resolvePath(URI base, String path) {
        try {
            URI relative = URI.create(path);
            String decoded = relative.getPath();
            if (!path.startsWith("/") || path.startsWith("//") || relative.getRawAuthority() != null
                    || relative.getRawQuery() != null || relative.getRawFragment() != null || decoded == null || decoded.startsWith("//")
                    || decoded.contains("\\") || decoded.codePoints().anyMatch(Character::isISOControl)
                    || Arrays.stream(decoded.split("/")).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
                throw new IllegalArgumentException();
            }
            return base.resolve(relative).toASCIIString();
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter paths beginning with a single slash, without query strings, fragments or dot segments.");
        }
    }

    private static List<Check> checks(JsonNode proposal, int pathCount) {
        if (pathCount == 0) {
            var check = readCheck(proposal, true);
            return "UNSUPPORTED".equals(check.method()) ? List.of() : List.of(check);
        }
        var steps = proposal.path("steps");
        if (!proposal.isObject() || proposal.size() != 1 || !steps.isArray()
                || (!steps.isEmpty() && steps.size() != pathCount)) throw malformed();
        var checks = new ArrayList<Check>();
        for (var step : steps) checks.add(readCheck(step, false));
        return List.copyOf(checks);
    }

    private static Check readCheck(JsonNode proposal, boolean allowUnsupported) {
        String method = proposal.path("method").textValue();
        JsonNode status = proposal.path("expectedStatus");
        if (!proposal.isObject() || proposal.size() != 2
                || !("GET".equals(method) || "HEAD".equals(method) || allowUnsupported && "UNSUPPORTED".equals(method))
                || (!status.isNull() && (!status.isIntegralNumber() || !status.canConvertToInt()
                || status.intValue() < 200 || status.intValue() > 599))) throw malformed();
        return new Check(method, status.isNull() ? null : status.intValue());
    }

    private String render(List<Check> checks, List<String> urls, int timeout) throws JsonProcessingException {
        var script = new StringBuilder("""
                import http from 'k6/http';
                import { check } from 'k6';

                export default function () {
                """);
        for (int index = 0; index < checks.size(); index++) {
            var check = checks.get(index);
            String name = checks.size() == 1 ? "response" : "response" + (index + 1);
            String label = checks.size() == 1 ? "expected HTTP status" : "request " + (index + 1) + " expected HTTP status";
            String condition = check.expectedStatus() == null ? "r.status >= 200 && r.status < 400" : "r.status === " + check.expectedStatus();
            script.append("  const %s = http.%s(%s, { timeout: %d, redirects: 0 });\n".formatted(name,
                    check.method().toLowerCase(Locale.ROOT), json.writeValueAsString(urls.get(index)), timeout));
            script.append("  check(%s, { '%s': (r) => %s });\n".formatted(name, label, condition));
        }
        return script.append("}\n").toString();
    }

    private ObjectNode schema(int pathCount) {
        var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        var response = schema;
        if (pathCount > 0) {
            schema.putArray("required").add("steps");
            response = schema.putObject("properties").putObject("steps").put("type", "array").put("maxItems", pathCount)
                    .put("description", "Return " + pathCount + " ordered checks, or an empty array when the goal is unsupported.")
                    .putObject("items").put("type", "object").put("additionalProperties", false);
        }
        response.putArray("required").add("method").add("expectedStatus");
        var properties = response.putObject("properties");
        var methods = properties.putObject("method").put("type", "string").putArray("enum").add("GET").add("HEAD");
        if (pathCount == 0) methods.add("UNSUPPORTED");
        var status = properties.putObject("expectedStatus").put("minimum", 200).put("maximum", 599);
        status.putArray("type").add("integer").add("null");
        return schema;
    }

    private static ResponseStatusException malformed() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned an unsupported script proposal.");
    }
}
