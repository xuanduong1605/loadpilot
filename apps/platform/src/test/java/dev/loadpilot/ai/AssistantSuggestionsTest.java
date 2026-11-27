package dev.loadpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantSuggestionsTest {
    @Test @Timeout(10) void sharesAnOverlappingRequestWithinOneOperatorAndReportsBusySeparately() throws Exception {
        var provider = mock(AssistantProvider.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("gemini", provider);
        var meters = new SimpleMeterRegistry();
        var suggestions = new AssistantSuggestions(beans.getBeanProvider(AssistantProvider.class), "", meters);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var duplicated = new CountDownLatch(1);
        var schema = new ObjectMapper().createObjectNode();
        when(provider.proposeSettings(any(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new TimeoutException();
            return "{\"changes\":[]}";
        });

        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = clients.submit(() -> suggestions.proposeSettings("alice", "First", schema));
                var second = clients.submit(() -> suggestions.proposeSettings("alice", "Second", schema));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var duplicate = clients.submit(() -> {
                    duplicated.countDown();
                    return suggestions.proposeSettings("alice", "First", schema);
                });
                assertThat(suggestions.proposeSettings("bob", "First", schema).issue()).isEqualTo(AssistantProvider.Issue.BUSY);
                assertThat(duplicated.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> duplicate.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(duplicate.get(2, TimeUnit.SECONDS)).isEqualTo(first.get(2, TimeUnit.SECONDS));
                assertThat(second.get(2, TimeUnit.SECONDS).issue()).isNull();
                verify(provider).proposeSettings("First", schema);
                verify(provider).proposeSettings("Second", schema);
                assertThat(meters.get("loadpilot.assistant.provider.saturated").tag("operation", "settings").counter().count()).isEqualTo(1.0);
            } finally { release.countDown(); }
        } finally {
            suggestions.close();
            meters.close();
        }
    }
}
