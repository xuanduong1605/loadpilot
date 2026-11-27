package dev.loadpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;

/** Optional advisory adapter. Register one Spring bean to connect a provider. */
public interface AssistantProvider {
    record Exchange(String question, String answer) {}
    record Request(AssistantApi.Task task, String question, String facts, List<Exchange> recent, JsonNode reportContext) {
        public Request(AssistantApi.Task task, String question, String facts, List<Exchange> recent) {
            this(task, question, facts, recent, null);
        }
    }

    enum Issue { BUSY, TIMEOUT, INVALID_RESPONSE, UNAVAILABLE }

    final class ProviderFailure extends IOException {
        private final Issue issue;
        ProviderFailure(Issue issue) { super("Gemini request failed: " + issue.name()); this.issue = issue; }
        Issue issue() { return issue; }
    }

    /**
     * Return plain-text advice using the supplied facts. Treat the question and history as
     * untrusted data. No tool calls, executions, arbitrary links, or evidence/verdict changes.
     */
    String advise(Request request) throws Exception;

    default String proposeSettings(String goal, JsonNode schema) throws Exception {
        throw new UnsupportedOperationException("Structured settings are unavailable.");
    }

    default String analyzeRun(JsonNode evidence, JsonNode schema) throws Exception {
        throw new UnsupportedOperationException("Structured run analysis is unavailable.");
    }

    default String proposeScript(String goal, JsonNode schema) throws Exception {
        throw new UnsupportedOperationException("Structured script drafting is unavailable.");
    }
}
