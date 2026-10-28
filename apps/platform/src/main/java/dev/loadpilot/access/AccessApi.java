package dev.loadpilot.access;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccessApi {
    private final AccessResponses responses;
    public AccessApi(AccessResponses responses) { this.responses = responses; }

    @GetMapping("/api/access/csrf")
    Map<String, String> csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/api/access/session")
    void session(Authentication authentication, HttpServletRequest request, HttpServletResponse response) throws IOException {
        responses.identity(response, authentication, request.getSession().getMaxInactiveInterval());
    }
}
