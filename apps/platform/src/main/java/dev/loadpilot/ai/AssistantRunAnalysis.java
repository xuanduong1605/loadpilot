package dev.loadpilot.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.experiments.ExperimentSnapshots.Inputs;
import dev.loadpilot.experiments.RunResults;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class AssistantRunAnalysis {
    enum Status { READY, INSUFFICIENT_DATA, PROVIDER_UNAVAILABLE }
    enum EvidenceId { WHOLE_RUN, PARTIAL_HTTP, COMPARISON_WHOLE_RUN }

    record Observation(EvidenceId id, Instant from, Instant until, long httpRequests, double p95LatencyMs,
                       double httpFailureRatio, Double httpRequestsPerSecond) {}
    record Finding(String hypothesis, String suggestedCheck, List<EvidenceId> evidenceIds) {}
    record Comparison(UUID runId, String runStatus, String verdict, int requestedIterationsPerSecond) {}
    record Report(Status status, String runStatus, String verdict, List<Observation> observations, List<Finding> findings,
                  @JsonInclude(JsonInclude.Include.NON_NULL) Comparison comparison) {}

    private final ObjectMapper json;
    private final AssistantSuggestions suggestions;

    AssistantRunAnalysis(ObjectMapper json, AssistantSuggestions suggestions) {
        this.json = json;
        this.suggestions = suggestions;
    }

    Report analyze(RunResults.View view) {
        List<Observation> observations = observations(view);
        if (observations.isEmpty()) return report(Status.INSUFFICIENT_DATA, view, observations, List.of(), null);
        return askProvider(view, observations, null);
    }

    Report compare(RunResults.View baseline, RunResults.View candidate, Inputs first, Inputs second) {
        if (!comparable(first, second)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Choose runs with the same registered system revision, script content, requested load, and measurement and execution settings.");
        var comparison = new Comparison(candidate.runId(), candidate.status(), candidate.verdict(), first.measurementRate());
        var paired = new ArrayList<Observation>();
        wholeObservation(baseline, EvidenceId.WHOLE_RUN).ifPresent(paired::add);
        wholeObservation(candidate, EvidenceId.COMPARISON_WHOLE_RUN).ifPresent(paired::add);
        var observations = List.copyOf(paired);
        if (observations.size() != 2 || !"COMPLETED".equals(baseline.status()) || !"COMPLETED".equals(candidate.status())) {
            return report(Status.INSUFFICIENT_DATA, baseline, observations, List.of(), comparison);
        }
        return askProvider(baseline, observations, comparison);
    }

    private static boolean comparable(Inputs first, Inputs second) {
        return comparisonInput(first) && comparisonInput(second)
                && first.target().equals(second.target()) && first.scenario().script().sha256().equals(second.scenario().script().sha256())
                && first.measurementRate().equals(second.measurementRate()) && first.measurementSeconds() == second.measurementSeconds()
                && first.loadProfile().equals(second.loadProfile()) && first.executor().equals(second.executor())
                && first.executionBounds().equals(second.executionBounds()) && first.sloPolicy().equals(second.sloPolicy())
                && Objects.equals(first.settings().durationSeconds(), second.settings().durationSeconds())
                && Objects.equals(first.settings().warmupSeconds(), second.settings().warmupSeconds());
    }

    private static boolean comparisonInput(Inputs input) {
        return input != null && "experiment-input-v5".equals(input.schemaVersion()) && "SINGLE_RUN".equals(input.mode())
                && "iterations/s".equals(input.loadUnit()) && input.measurementRate() != null && input.measurementRate() > 0
                && input.target() != null && input.scenario() != null && input.scenario().script() != null
                && input.scenario().script().sha256() != null && input.settings() != null && input.executor() != null
                && input.loadProfile() != null && input.executionBounds() != null && input.sloPolicy() != null;
    }

    private Report askProvider(RunResults.View view, List<Observation> observations, Comparison comparison) {

        var evidence = json.createObjectNode();
        if (comparison != null) evidence.put("requestedIterationsPerSecond", comparison.requestedIterationsPerSecond());
        var metrics = evidence.putArray("observations");
        for (var item : observations) {
            var observation = metrics.addObject().put("id", item.id().name())
                    .put("httpRequests", item.httpRequests()).put("p95LatencyMs", item.p95LatencyMs())
                    .put("httpFailureRatio", item.httpFailureRatio());
            if (item.httpRequestsPerSecond() != null) observation.put("httpRequestsPerSecond", item.httpRequestsPerSecond());
        }
        var allowedIds = observations.stream().map(Observation::id).toList();
        String reply = suggestions.analyzeRun(evidence, schema(allowedIds, comparison != null));
        if (reply != null) {
            try {
                return report(Status.READY, view, observations, findings(reply, allowedIds, comparison != null), comparison);
            } catch (JsonProcessingException | IllegalArgumentException invalid) {
                // Provider output is untrusted. Preserve the saved observations without its findings.
            }
        }
        return report(Status.PROVIDER_UNAVAILABLE, view, observations, List.of(), comparison);
    }

    private static List<Observation> observations(RunResults.View view) {
        var observations = new ArrayList<Observation>();
        wholeObservation(view, EvidenceId.WHOLE_RUN).ifPresent(observations::add);
        var window = view.partialHttp();
        if (window != null && window.requestCount() > 0 && window.errorCount() >= 0 && window.errorCount() <= window.requestCount()
                && window.errorRatio() == (double) window.errorCount() / window.requestCount()
                && nonnegative(window.p95LatencyMs()) && Double.isFinite(window.requestsPerSecond()) && window.requestsPerSecond() > 0
                && validWindow(window.windowFrom(), window.windowUntil())) {
            observations.add(new Observation(EvidenceId.PARTIAL_HTTP, window.windowFrom(), window.windowUntil(),
                    window.requestCount(), window.p95LatencyMs(), window.errorRatio(), window.requestsPerSecond()));
        }
        return List.copyOf(observations);
    }

    private static Optional<Observation> wholeObservation(RunResults.View view, EvidenceId id) {
        if (view.result() != null && "AVAILABLE".equals(view.resultState())) {
            var evidence = view.result().evidence();
            var summary = evidence.summary();
            if (summary != null && summary.httpRequests() != null && summary.httpRequests() > 0
                    && summary.httpDurationMs() != null && nonnegative(summary.httpDurationMs().p95())
                    && ratio(summary.k6HttpFailureRate()) && validWindow(evidence.startedAt(), evidence.finishedAt())) {
                return Optional.of(new Observation(id, evidence.startedAt(), evidence.finishedAt(),
                        summary.httpRequests(), summary.httpDurationMs().p95(), summary.k6HttpFailureRate(), null));
            }
        }
        return Optional.empty();
    }

    private static boolean validWindow(Instant from, Instant until) {
        return from != null && until != null && from.isBefore(until) && !until.isAfter(Instant.now().plusSeconds(5));
    }

    private static boolean nonnegative(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }

    private static boolean ratio(Double value) {
        return nonnegative(value) && value <= 1;
    }

    private List<Finding> findings(String reply, List<EvidenceId> allowedIds, boolean comparing) throws JsonProcessingException {
        JsonNode root = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(reply);
        var entries = root.path("findings");
        if (!root.isObject() || root.size() != 1 || !entries.isArray() || entries.size() > 3) throw invalid();
        var findings = new ArrayList<Finding>();
        for (var entry : entries) {
            var ids = entry.path("evidenceIds");
            if (!entry.isObject() || entry.size() != 3 || !ids.isArray() || ids.isEmpty() || ids.size() > allowedIds.size()
                    || (comparing && ids.size() != allowedIds.size())) throw invalid();
            var citations = new ArrayList<EvidenceId>();
            for (var value : ids) {
                if (!value.isTextual()) throw invalid();
                var id = EvidenceId.valueOf(value.textValue());
                if (!allowedIds.contains(id) || citations.contains(id)) throw invalid();
                citations.add(id);
            }
            findings.add(new Finding(text(entry.path("hypothesis")), text(entry.path("suggestedCheck")), List.copyOf(citations)));
        }
        return List.copyOf(findings);
    }

    private String text(JsonNode node) {
        String value = node.isTextual() ? node.textValue().strip() : "";
        if (value.isEmpty() || value.length() > 280 || suggestions.containsCredentials(value)
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    private ObjectNode schema(List<EvidenceId> allowedIds, boolean comparing) {
        var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("findings");
        var items = schema.putObject("properties").putObject("findings").put("type", "array").put("maxItems", 3)
                .putObject("items").put("type", "object").put("additionalProperties", false);
        items.putArray("required").add("hypothesis").add("suggestedCheck").add("evidenceIds");
        var contents = items.putObject("properties");
        contents.putObject("hypothesis").put("type", "string");
        contents.putObject("suggestedCheck").put("type", "string");
        var citations = contents.putObject("evidenceIds").put("type", "array").put("minItems", comparing ? allowedIds.size() : 1)
                .put("maxItems", allowedIds.size())
                .putObject("items").put("type", "string").putArray("enum");
        allowedIds.forEach(id -> citations.add(id.name()));
        return schema;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid run analysis response."); }

    private static Report report(Status status, RunResults.View view, List<Observation> observations, List<Finding> findings, Comparison comparison) {
        return new Report(status, view.status(), view.verdict(), observations, findings, comparison);
    }
}
