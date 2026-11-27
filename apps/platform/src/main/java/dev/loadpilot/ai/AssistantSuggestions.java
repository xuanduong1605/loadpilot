package dev.loadpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dev.loadpilot.ai.AssistantApi.*;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AssistantSuggestions {
    private static final int OUTPUT_LIMIT = 4000;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)-----BEGIN [^-\\r\\n]{0,48}PRIVATE KEY-----|\\b(?:bearer|basic)\\s+[a-z0-9+/=_.-]{8,}"
            + "|\\b(?:[a-z0-9]+_)*(?:password|passwd|token|secret|api[_ -]?key)[\"']?\\s*[:=]"
            + "|\\bsk-[a-z0-9_-]{12,}|\\bAIza[a-z0-9_-]{35}(?![a-z0-9_-])|https?://[^\\s/]+@");
    private final AssistantProvider provider;
    private final String geminiKey;
    private final ExecutorService calls = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofVirtual().name("assistant-provider-", 0).factory());

    AssistantSuggestions(ObjectProvider<AssistantProvider> providers, @Value("${GEMINI_API_KEY:}") String geminiKey) {
        provider = providers.getIfUnique();
        this.geminiKey = geminiKey.strip();
    }

    record Suggestion(String text, boolean unavailable) {}

    boolean available() { return provider != null; }

    boolean containsCredentials(String text) {
        return SENSITIVE.matcher(text).find() || (!geminiKey.isEmpty() && text.contains(geminiKey));
    }

    Suggestion suggest(Message message, AssistantGuide.Answer guidance, List<Turn> recent) {
        if (provider == null || !guidance.offerAdvice())
            return new Suggestion(null, false);
        var context = recent.stream()
                .filter(turn -> turn.task() == message.task() && java.util.Objects.equals(turn.recordId(), message.recordId())
                        && !containsCredentials(turn.text())
                        && turn.reply().suggestion() != null && !containsCredentials(turn.reply().suggestion()))
                .map(turn -> new AssistantProvider.Exchange(turn.text(), turn.reply().suggestion()))
                .toList();
        var request = new AssistantProvider.Request(message.task(), message.text(), guidance.facts(), context);
        String answer = call(() -> provider.advise(request));
        return new Suggestion(answer, answer == null);
    }

    String proposeSettings(String goal, JsonNode schema) {
        return provider == null ? null : call(() -> provider.proposeSettings(goal, schema));
    }

    String analyzeRun(JsonNode evidence, JsonNode schema) {
        return provider == null ? null : call(() -> provider.analyzeRun(evidence, schema));
    }

    String proposeScript(String goal, JsonNode schema) {
        return provider == null ? null : call(() -> provider.proposeScript(goal, schema));
    }

    private String call(Callable<String> task) {
        Future<String> call;
        try {
            call = calls.submit(task);
        } catch (RejectedExecutionException busy) {
            return null;
        }
        try {
            String answer = call.get(8, TimeUnit.SECONDS);
            if (answer == null || answer.isBlank() || answer.length() > OUTPUT_LIMIT || containsCredentials(answer)
                    || answer.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t'))
                return null;
            return answer.strip();
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException | ExecutionException unavailable) {
            call.cancel(true);
            return null;
        }
    }

    @PreDestroy
    void close() { calls.shutdownNow(); }
}
