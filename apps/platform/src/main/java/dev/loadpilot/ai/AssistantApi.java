package dev.loadpilot.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/assistant")
public class AssistantApi {
    public enum Task { ASK, SCRIPT, STATUS, START, RESULT, EDIT }
    public enum Destination { SYSTEMS, SCRIPTS, SCRIPT_EDITOR, RUNS, RUN_MONITOR, RUN_EVIDENCE, INPUTS, INPUT_REVIEW, SETUP }

    public record Link(Destination destination, UUID recordId, Integer version, String label) {}
    public record Reply(String text, List<Link> links, String suggestion, boolean providerUnavailable) {}
    public record Message(@NotNull UUID requestId, @NotNull @Min(0) @Max(50) Integer expectedTurns,
                          @NotBlank @Size(max = 2000) String text, @NotNull Task task, UUID recordId) {}
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

    public AssistantApi(AssistantHistory history, AssistantGuide guide, AssistantSuggestions suggestions) {
        this.history = history;
        this.guide = guide;
        this.suggestions = suggestions;
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

    @PostMapping("/conversations/{id}/messages")
    public Sent send(@PathVariable UUID id, @Valid @RequestBody Message request, Principal actor) {
        if (request.text().codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t'))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remove control characters from the message.");
        if (AssistantSuggestions.containsCredentials(request.text()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remove credentials from your message before sending it.");
        var state = history.inspect(id, actor.getName(), request);
        if (state.replay() != null) return state.replay();
        var answer = guide.answer(request.task(), request.recordId(), request.text(), actor.getName());
        var suggestion = suggestions.suggest(request, answer, state.recent());
        var reply = new Reply(answer.text(), answer.links(), suggestion.text(), suggestion.unavailable());
        return history.append(id, actor.getName(), request, reply);
    }
}
