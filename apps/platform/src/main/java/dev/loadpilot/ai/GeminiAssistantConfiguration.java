package dev.loadpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@Conditional(GeminiAssistantConfiguration.KeyConfigured.class)
class GeminiAssistantConfiguration {
    static final String KEY_PROPERTY = "GEMINI_API_KEY";
    private static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private static final HttpUrl API = HttpUrl.get("https://generativelanguage.googleapis.com/");

    @Bean
    GeminiAssistantProvider geminiAssistantProvider(ObjectMapper json, Environment environment) {
        String key = environment.getRequiredProperty(KEY_PROPERTY).strip();
        String model = environment.getProperty("GEMINI_MODEL", DEFAULT_MODEL).strip();
        return new GeminiAssistantProvider(json, key, model.isEmpty() ? DEFAULT_MODEL : model, API);
    }

    static class KeyConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty(KEY_PROPERTY));
        }
    }
}
