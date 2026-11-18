package dev.loadpilot.ai;

import dev.loadpilot.ai.AssistantApi.*;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class AssistantSuggestions {
    private static final int OUTPUT_LIMIT = 4000;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)-----BEGIN [^-\\r\\n]{0,48}PRIVATE KEY-----|\\b(?:bearer|basic)\\s+[a-z0-9+/=_.-]{8,}"
            + "|\\b(?:[a-z0-9]+_)*(?:password|passwd|token|secret|api[_ -]?key)[\"']?\\s*[:=]"
            + "|\\bsk-[a-z0-9_-]{12,}|https?://[^\\s/]+@");
    private final AssistantProvider provider;
    private final ExecutorService calls = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofVirtual().name("assistant-provider-", 0).factory());

    AssistantSuggestions(ObjectProvider<AssistantProvider> providers) {
        provider = providers.getIfUnique();
    }

    record Suggestion(String text, boolean unavailable) {}

    boolean available() { return provider != null; }

    static boolean containsCredentials(String text) { return SENSITIVE.matcher(text).find(); }

    Suggestion suggest(Message message, AssistantGuide.Answer guidance, List<Turn> recent) {
        if (provider == null || !guidance.offerAdvice())
            return new Suggestion(null, false);
        var context = recent.stream()
                .filter(turn -> turn.task() == message.task() && java.util.Objects.equals(turn.recordId(), message.recordId())
                        && !containsCredentials(turn.text()))
                .map(turn -> new AssistantProvider.Exchange(turn.text(), turn.reply().suggestion()))
                .toList();
        var request = new AssistantProvider.Request(message.task(), message.text(), guidance.facts(), context);
        Future<String> call;
        try {
            call = calls.submit(() -> provider.advise(request));
        } catch (RejectedExecutionException busy) {
            return new Suggestion(null, true);
        }
        try {
            String answer = call.get(8, TimeUnit.SECONDS);
            if (answer == null || answer.isBlank() || answer.length() > OUTPUT_LIMIT || containsCredentials(answer)
                    || answer.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t'))
                return new Suggestion(null, true);
            return new Suggestion(answer.strip(), false);
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return new Suggestion(null, true);
        } catch (TimeoutException | ExecutionException unavailable) {
            call.cancel(true);
            return new Suggestion(null, true);
        }
    }

    @PreDestroy
    void close() { calls.shutdownNow(); }
}
