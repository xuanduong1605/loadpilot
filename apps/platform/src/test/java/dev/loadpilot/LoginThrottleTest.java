package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.access.AccessResponses;
import dev.loadpilot.access.AccessAudit;
import dev.loadpilot.access.LoginThrottle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LoginThrottleTest {
    @Test void capsAttemptsRegardlessOfUntrustedForwardedAddress() throws Exception {
        var audit = mock(AccessAudit.class);
        when(audit.record(any(), isNull(), eq("LOGIN"), eq("THROTTLED"))).thenReturn(true);
        var now = new java.util.concurrent.atomic.AtomicLong();
        var limiter = new LoginThrottle(new AccessResponses(new ObjectMapper()), audit, now::get);
        for (int attempt = 0; attempt < 12; attempt++) {
            var request = new MockHttpServletRequest("POST", "/api/access/login");
            request.setServletPath("/api/access/login");
            request.addHeader("X-Forwarded-For", "test-" + attempt);
            var response = new MockHttpServletResponse();
            limiter.doFilter(request, response, (req, res) -> res.getWriter().write("allowed"));
            assertThat(response.getStatus()).isEqualTo(attempt < 10 ? 200 : 429);
            if (attempt >= 10) assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        }
        verify(audit, times(1)).record(any(), isNull(), eq("LOGIN"), eq("THROTTLED"));
        now.set(60_000_000_000L);
        var request = new MockHttpServletRequest("POST", "/api/access/login");
        request.setServletPath("/api/access/login");
        var response = new MockHttpServletResponse();
        limiter.doFilter(request, response, (req, res) -> res.getWriter().write("allowed"));
        assertThat(response.getContentAsString()).isEqualTo("allowed");
    }
}
