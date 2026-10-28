package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.access.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class IdleSessionTest {
    @Test void backgroundInspectionDoesNotRefreshIdleClockAndExpiredSessionIsRevoked() throws Exception {
        var filter = new IdleSessionFilter(new AccessResponses(new ObjectMapper()));
        var session = new MockHttpSession(); session.setMaxInactiveInterval(30);
        long previous = System.currentTimeMillis()-10_000;
        session.setAttribute(IdleSessionFilter.LAST_INTERACTIVE, previous);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin",null,List.of()));
        try {
            var request = new MockHttpServletRequest("GET","/api/integrations/targets/fixture/status"); request.setSession(session);
            var response = new MockHttpServletResponse();
            filter.doFilter(request,response,(req,res) -> res.getWriter().write("inspected"));
            assertThat(session.getAttribute(IdleSessionFilter.LAST_INTERACTIVE)).isEqualTo(previous);
            session.setAttribute(IdleSessionFilter.LAST_INTERACTIVE,System.currentTimeMillis()-31_000);
            var expired = new MockHttpServletRequest("GET","/api/integrations/targets/fixture/status"); expired.setSession(session);
            response = new MockHttpServletResponse();
            filter.doFilter(expired,response,(req,res) -> { throw new AssertionError("Expired request reached adapter"); });
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(session.isInvalid()).isTrue();
        } finally { SecurityContextHolder.clearContext(); }
    }
}
