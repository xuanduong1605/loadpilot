package dev.loadpilot.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.loadpilot.ai.AssistantProvider.Issue;
import dev.loadpilot.experiments.ExperimentLibrary;
import dev.loadpilot.experiments.RunResults;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/assistant")
public class AssistantApi {
    public enum Task { ASK, SCRIPT, STATUS, START, RESULT, EDIT, REPORT }
    public enum Destination { SYSTEMS, SCRIPTS, SCRIPT_EDITOR, RUNS, RUN_MONITOR, RUN_EVIDENCE, INPUTS, INPUT_REVIEW, SETUP }

    public record Link(Destination destination, UUID recordId, Integer version, String label) {}
    public record Reply(String text, List<Link> links, String suggestion, boolean providerUnavailable,
                        @JsonInclude(JsonInclude.Include.NON_NULL) AssistantRunAnalysis.Report analysis,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Issue providerIssue,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean reportFollowUp) {}
    public record Message(@NotNull UUID requestId, @NotNull @Min(0) @Max(50) Integer expectedTurns,
                          @NotBlank @Size(max = 2000) String text, @NotNull Task task, UUID recordId) {}
    public record AnalysisRequest(@NotNull UUID requestId, @NotNull @Min(0) @Max(50) Integer expectedTurns,
                                  @NotNull UUID runId, UUID comparisonRunId) {
        public AnalysisRequest(UUID requestId, Integer expectedTurns, UUID runId) {
            this(requestId, expectedTurns, runId, null);
        }
    }
    public record Turn(int number, UUID requestId, String text, Task task, UUID recordId,
                       Reply reply, OffsetDateTime createdAt) {}
    public record Summary(UUID id, String title, int turnCount, OffsetDateTime updatedAt) {}
    public record History(List<Summary> items, boolean hasMore) {}
    public record Conversation(Summary summary, List<Turn> turns) {}
    public record Sent(Summary summary, Turn turn) {}
    public record Capabilities(boolean providerAvailable) {}

    private final AssistantHistory history;
    private final AssistantGuide guide;
    private final AssistantSuggestions suggestions;
    private final AssistantRunAnalysis runAnalysis;
    private final ExperimentLibrary library;
    private final RunResults results;

    public AssistantApi(AssistantHistory history, AssistantGuide guide, AssistantSuggestions suggestions, AssistantRunAnalysis runAnalysis,
                        ExperimentLibrary library, RunResults results) {
        this.history = history;
        this.guide = guide;
        this.suggestions = suggestions;
        this.runAnalysis = runAnalysis;
        this.library = library;
        this.results = results;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(suggestions.available());
    }

    @GetMapping("/conversations")
    public History history(@RequestParam(defaultValue = "0") int offset, Principal actor) {
        if (offset < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a nonnegative history offset.");
        return history.list(actor.getName(), offset);
    }

    @GetMapping("/conversations/{id}")
    public Conversation conversation(@PathVariable UUID id, Principal actor) {
        return history.conversation(id, actor.getName());
    }

    @GetMapping("/runs/{id}/comparison-options")
    public ExperimentLibrary.Page comparisonOptions(@PathVariable UUID id, @RequestParam(defaultValue = "0") int offset, Principal actor) {
        if (offset < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a nonnegative history offset.");
        String operator = actor.getName();
        var view = guide.ownedRunResult(id, operator);
        if (!"COMPLETED".equals(view.status()) || !AssistantRunAnalysis.usableWhole(view.result())) {
            return new ExperimentLibrary.Page(List.of(), false);
        }
        var baseline = guide.runInputs(id);
        if (!AssistantRunAnalysis.comparable(baseline, baseline)) return new ExperimentLibrary.Page(List.of(), false);
        var page = library.comparisonPage(operator, id, baseline.target().id(), offset, 20);
        var eligible = page.items().stream()
                .filter(entry -> AssistantRunAnalysis.comparable(baseline, guide.runInputs(entry.id()))
                        && AssistantRunAnalysis.usableWhole(results.saved(entry.id())))
                .toList();
        return new ExperimentLibrary.Page(eligible, page.hasMore());
    }

    @PostMapping("/conversations/{id}/messages")
    public Sent send(@PathVariable UUID id, @Valid @RequestBody Message request, Principal actor) {
        if (request.text().codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t'))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remove control characters from the message.");
        if (suggestions.containsCredentials(request.text()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remove credentials from your message before sending it.");
        String operator = actor.getName();
        var state = history.inspect(id, operator, request);
        if (state.replay() != null) return replay(state.replay(), false);
        var report = request.task() == Task.REPORT ? runAnalysis.followUp(history.report(id, operator, request.recordId())) : null;
        var answer = guide.answer(request.task(), request.recordId(), request.text(), operator);
        var suggestion = suggestions.suggest(operator, request, answer, state.recent(), report);
        var reply = new Reply(answer.text(), answer.links(), suggestion.text(), suggestion.issue() != null, null,
                suggestion.issue(), report == null ? null : true);
        return replay(history.append(id, operator, request, reply), false);
    }

    @PostMapping("/conversations/{id}/analysis")
    public Sent analyze(@PathVariable UUID id, @Valid @RequestBody AnalysisRequest input, Principal actor) {
        String operator = actor.getName();
        UUID comparisonId = input.comparisonRunId();
        if (input.runId().equals(comparisonId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose two different runs.");
        boolean comparing = comparisonId != null;
        var request = new Message(input.requestId(), input.expectedTurns(), comparing ? "Compare saved run evidence with Gemini."
                : "Analyze saved run evidence with Gemini.", Task.RESULT, input.runId());
        var state = history.inspect(id, operator, request);
        if (state.replay() != null) return analysisReplay(state.replay(), comparisonId);
        var view = guide.ownedRunResult(input.runId(), operator);
        var report = comparing ? runAnalysis.compare(operator, view, guide.ownedRunResult(comparisonId, operator),
                guide.runInputs(input.runId()), guide.runInputs(comparisonId)) : runAnalysis.analyze(operator, view);
        var links = comparing ? List.of(new Link(Destination.RUN_EVIDENCE, input.runId(), null, "Open baseline evidence"),
                new Link(Destination.RUN_EVIDENCE, comparisonId, null, "Open comparison evidence"))
                : List.of(new Link(Destination.RUN_EVIDENCE, input.runId(), null, "Open run evidence"));
        String text = report.status() == AssistantRunAnalysis.Status.INSUFFICIENT_DATA
                ? (comparing ? "Both runs need a saved whole-run HTTP summary for comparison." : "There is insufficient saved HTTP evidence for analysis.")
                : (comparing ? "Review the paired historical HTTP observations and each recorded SLO verdict."
                : "Review the saved HTTP observations and recorded SLO verdict.");
        var reply = new Reply(text, links, null,
                report.status() == AssistantRunAnalysis.Status.PROVIDER_UNAVAILABLE, report, report.providerIssue(), null);
        return analysisReplay(history.append(id, operator, request, reply), comparisonId);
    }

    private static Sent analysisReplay(Sent saved, UUID requestedComparison) {
        var comparison = replay(saved, true).turn().reply().analysis().comparison();
        if (!Objects.equals(requestedComparison, comparison == null ? null : comparison.runId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request ID was used with a different comparison run.");
        }
        return saved;
    }

    private static Sent replay(Sent replay, boolean analysisRequest) {
        if ((replay.turn().reply().analysis() != null) != analysisRequest) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request ID was used for a different Assistant action.");
        }
        return replay;
    }
}
