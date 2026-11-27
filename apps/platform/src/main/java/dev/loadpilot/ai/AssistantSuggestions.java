package dev.loadpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dev.loadpilot.ai.AssistantApi.*;
import dev.loadpilot.ai.AssistantProvider.Issue;
import dev.loadpilot.ai.AssistantProvider.ProviderFailure;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class AssistantSuggestions {
    private static final int OUTPUT_LIMIT = 4000;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)-----BEGIN [^-\\r\\n]{0,48}PRIVATE KEY-----|\\b(?:bearer|basic)\\s+[a-z0-9+/=_.-]{8,}"
            + "|\\b(?:[a-z0-9]+_)*(?:password|passwd|token|secret|api[_ -]?key)[\"']?\\s*[:=]"
            + "|\\bsk-[a-z0-9_-]{12,}|\\bAIza[a-z0-9_-]{35}(?![a-z0-9_-])|https?://[^\\s/]+@");
    private final AssistantProvider provider;
    private final String geminiKey;
    private final MeterRegistry metrics;
    private final ConcurrentMap<Job, Flight> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService calls = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofVirtual().name("assistant-provider-", 0).factory());

    AssistantSuggestions(ObjectProvider<AssistantProvider> providers, @Value("${GEMINI_API_KEY:}") String geminiKey,
                         MeterRegistry metrics) {
        provider = providers.getIfUnique();
        this.geminiKey = geminiKey.strip();
        this.metrics = metrics;
    }

    private record Job(String operation, String operator, Object input) {}

    private final class Flight extends FutureTask<Result> {
        private final Job job;
        private volatile Issue cancellation = Issue.BUSY;

        Flight(Job job, Callable<String> task) {
            super(() -> perform(job.operation(), task));
            this.job = job;
        }

        @Override protected void done() { inFlight.remove(job, this); }

        void timeOut() {
            cancellation = Issue.TIMEOUT;
            cancel(true);
        }
    }

    record Result(String text, Issue issue) {
        static Result failed(Issue issue) { return new Result(null, issue); }

        String require(String recovery) {
            if (issue == null) return text;
            var status = switch (issue) {
                case BUSY -> HttpStatus.TOO_MANY_REQUESTS;
                case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                case INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
                case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            var message = switch (issue) {
                case BUSY -> "Gemini is handling too many requests.";
                case TIMEOUT -> "Gemini took too long to reply.";
                case INVALID_RESPONSE -> "Gemini returned an unusable response.";
                case UNAVAILABLE -> "Gemini is currently unavailable.";
            };
            throw new ResponseStatusException(status, message + " " + recovery);
        }
    }

    boolean available() { return provider != null; }

    boolean containsCredentials(String text) {
        return SENSITIVE.matcher(text).find() || (!geminiKey.isEmpty() && text.contains(geminiKey));
    }

    Result suggest(String operator, Message message, AssistantGuide.Answer guidance, List<Turn> recent, JsonNode report) {
        if (!guidance.offerAdvice()) return new Result(null, null);
        // Existing installations without a provider still offer the built-in chat guidance.
        if (provider == null && report == null) return new Result(null, null);
        var context = recent.stream()
                .filter(turn -> (turn.task() == message.task() || (message.task() == Task.ASK && message.recordId() == null))
                        && java.util.Objects.equals(turn.recordId(), message.recordId())
                        && !containsCredentials(turn.text())
                        && turn.reply().suggestion() != null && !containsCredentials(turn.reply().suggestion()))
                .map(turn -> new AssistantProvider.Exchange(turn.text(), turn.reply().suggestion()))
                .toList();
        var request = new AssistantProvider.Request(message.task(), message.text(), guidance.facts(), context, report);
        return call(new Job("chat", operator, request), () -> provider.advise(request));
    }

    Result proposeSettings(String operator, String goal, JsonNode schema) {
        return call(new Job("settings", operator, goal), () -> provider.proposeSettings(goal, schema));
    }

    Result analyzeRun(String operator, JsonNode evidence, JsonNode schema) {
        return call(new Job("analysis", operator, evidence), () -> provider.analyzeRun(evidence, schema));
    }

    Result proposeScript(String operator, String goal, JsonNode schema) {
        return call(new Job("script", operator, goal), () -> provider.proposeScript(goal, schema));
    }

    private Result call(Job job, Callable<String> task) {
        if (provider == null) return Result.failed(Issue.UNAVAILABLE);
        var candidate = new Flight(job, task);
        var existing = inFlight.putIfAbsent(job, candidate);
        var active = existing == null ? candidate : existing;
        if (existing == null) {
            try {
                calls.execute(candidate);
            } catch (RejectedExecutionException busy) {
                candidate.cancel(false);
                metrics.counter("loadpilot.assistant.provider.saturated", "operation", job.operation()).increment();
            }
        }
        try {
            return active.get(8, TimeUnit.SECONDS);
        } catch (CancellationException cancelled) {
            return Result.failed(active.cancellation);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failed(Issue.UNAVAILABLE);
        } catch (TimeoutException timeout) {
            active.timeOut();
            return Result.failed(Issue.TIMEOUT);
        } catch (ExecutionException unavailable) {
            return Result.failed(Issue.UNAVAILABLE);
        }
    }

    private Result perform(String operation, Callable<String> task) {
        long started = System.nanoTime();
        Result result;
        try {
            String answer = task.call();
            if (answer == null || answer.isBlank() || answer.length() > OUTPUT_LIMIT || containsCredentials(answer)
                    || answer.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')) {
                result = Result.failed(Issue.INVALID_RESPONSE);
            } else result = new Result(answer.strip(), null);
        } catch (ProviderFailure failure) {
            result = Result.failed(failure.issue());
        } catch (Exception unavailable) {
            result = Result.failed(Issue.UNAVAILABLE);
        }
        metrics.timer("loadpilot.assistant.provider", "operation", operation,
                "outcome", result.issue() == null ? "OK" : result.issue().name()).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        return result;
    }

    @PreDestroy
    void close() { calls.shutdownNow(); }
}
