package dev.loadpilot.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class AccessResponses {
    private final ObjectMapper json;
    public AccessResponses(ObjectMapper json) { this.json = json; }

    public void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        write(response, Map.of("code", code, "message", message, "timestamp", Instant.now().toString()));
    }

    public void identity(HttpServletResponse response, Authentication authentication, int idleTimeoutSeconds) throws IOException {
        write(response, Map.of("username", authentication.getName(), "role", "OPERATOR", "idleTimeoutSeconds", idleTimeoutSeconds));
    }

    private void write(HttpServletResponse response, Object value) throws IOException {
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), value);
    }
}
