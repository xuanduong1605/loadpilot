package dev.loadpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiAssistantProviderTest {
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String KEY = "synthetic-gemini-key";
    private final ObjectMapper json = new ObjectMapper();

    @Test void registersOnlyWithAConfiguredKey() {
        var application = new ApplicationContextRunner().withUserConfiguration(GeminiAssistantConfiguration.class)
                .withBean(ObjectMapper.class, () -> json);
        application.withPropertyValues("GEMINI_API_KEY=").run(context -> assertThat(context).doesNotHaveBean(AssistantProvider.class));
        application.withPropertyValues("GEMINI_API_KEY=" + KEY)
                .run(context -> assertThat(context).hasSingleBean(AssistantProvider.class));
    }

    @Test void sendsABoundedTextRequestWithoutStoredRecordData() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.reply.set("""
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[
                    {"thought":true,"text":"private thinking"},{"text":"Start with "},{"text":"a bounded ramp."}]}}]}
                    """);
            try (var assistant = fixture.provider()) {
                assertThat(assistant.advise(question())).isEqualTo("Start with a bounded ramp.");
            }
            assertThat(fixture.method.get()).isEqualTo("POST");
            assertThat(fixture.path.get()).isEqualTo("/v1beta/models/" + MODEL + ":generateContent");
            assertThat(fixture.key.get()).isEqualTo(KEY);
            assertThat(fixture.body.get()).doesNotContain(KEY, "internal metric");
            var wire = json.readTree(fixture.body.get());
            assertThat(wire.has("tools")).isFalse();
            assertThat(wire.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(768);
            var prompt = json.readTree(wire.at("/contents/0/parts/0/text").textValue());
            assertThat(prompt.path("task").textValue()).isEqualTo("SCRIPT");
            assertThat(prompt.path("question").textValue()).isEqualTo("How should I revise the ramp?");
            assertThat(prompt.at("/recent/0/reply").textValue()).isEqualTo("Use finite phases.");
        }
    }

    @Test void rejectsHttpErrorsRedirectsAndUnusableReplies() throws Exception {
        try (var fixture = new Fixture(); var assistant = fixture.provider()) {
            fixture.status.set(429);
            fixture.reply.set(KEY);
            assertIssue(assistant, AssistantProvider.Issue.BUSY);
            fixture.status.set(302);
            assertIssue(assistant, AssistantProvider.Issue.UNAVAILABLE);
            fixture.status.set(503);
            assertIssue(assistant, AssistantProvider.Issue.UNAVAILABLE);
            fixture.status.set(200);
            for (String body : List.of("", "invalid json", "{}", "x".repeat(64 * 1024 + 1), """
                    {"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"truncated"}]}}]}
                    """, """
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"start"}}]}}]}
                    """)) {
                fixture.reply.set(body);
                assertIssue(assistant, AssistantProvider.Issue.INVALID_RESPONSE);
            }
            assertThat(fixture.calls.get()).isEqualTo(9);
        }
    }

    @Test void sendsOnlyTheGoalAndSchemaForSettings() throws Exception {
        try (var fixture = new Fixture(); var assistant = fixture.provider()) {
            var schema = json.createObjectNode().put("type", "object");
            fixture.reply.set("""
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\\"changes\\":[]}"}]}}]}
                    """);
            assertThat(assistant.proposeSettings("Use 5 iterations/s.", schema)).isEqualTo("{\"changes\":[]}");
            var wire = json.readTree(fixture.body.get());
            assertThat(wire.at("/generationConfig/responseMimeType").textValue()).isEqualTo("application/json");
            assertThat(wire.at("/generationConfig/responseJsonSchema")).isEqualTo(schema);
            assertThat(json.readTree(wire.at("/contents/0/parts/0/text").textValue()))
                    .isEqualTo(json.createObjectNode().put("goal", "Use 5 iterations/s."));
            assertThat(wire.has("tools")).isFalse();
        }
    }

    @Test @Timeout(15) void timesOutWhenTheProviderStallsAfterSendingHeaders() throws Exception {
        try (var fixture = new Fixture(); var assistant = fixture.provider()) {
            fixture.reply.set("The provider will stall before this body.");
            fixture.stallBody.set(true);
            assertIssue(assistant, AssistantProvider.Issue.TIMEOUT);
            assertThat(fixture.calls.get()).isOne();
        }
    }

    private static AssistantProvider.Request question() {
        return new AssistantProvider.Request(AssistantApi.Task.SCRIPT, "How should I revise the ramp?", "internal metric",
                List.of(new AssistantProvider.Exchange("How do I start?", "Use finite phases.")));
    }

    private static void assertIssue(AssistantProvider assistant, AssistantProvider.Issue issue) {
        assertThatThrownBy(() -> assistant.advise(question())).hasNoCause()
                .isInstanceOfSatisfying(AssistantProvider.ProviderFailure.class, failure -> assertThat(failure.issue()).isEqualTo(issue));
    }

    private class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger status = new AtomicInteger(200);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean stallBody = new AtomicBoolean();
        private final CountDownLatch releaseBody = new CountDownLatch(1);
        private final AtomicReference<String> reply = new AtomicReference<>("");
        private final AtomicReference<String> key = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<String> method = new AtomicReference<>();

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    method.set(exchange.getRequestMethod());
                    key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
                    path.set(exchange.getRequestURI().toString());
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    exchange.getResponseHeaders().set("Location", "/redirected");
                    exchange.getResponseHeaders().set("Retry-After", "0");
                    byte[] data = reply.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), data.length);
                    if (stallBody.get()) {
                        try {
                            releaseBody.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    }
                    exchange.getResponseBody().write(data);
                }
            });
            server.start();
        }

        GeminiAssistantProvider provider() {
            return new GeminiAssistantProvider(json, KEY, MODEL, HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort()));
        }

        @Override public void close() {
            releaseBody.countDown();
            server.stop(0);
        }
    }
}
