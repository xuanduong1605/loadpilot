package dev.loadpilot.access;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Background status refresh must not extend an operator's idle lifetime. */
public class IdleSessionFilter extends OncePerRequestFilter {
    public static final String LAST_INTERACTIVE = IdleSessionFilter.class.getName() + ".lastInteractive";
    private final AccessResponses responses;
    public IdleSessionFilter(AccessResponses responses) { this.responses = responses; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var session = request.getSession(false);
        if (session != null && authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            long now = System.currentTimeMillis();
            Object recorded = session.getAttribute(LAST_INTERACTIVE);
            long last = recorded instanceof Long value ? value : session.getCreationTime();
            if (session.getMaxInactiveInterval() > 0 && now - last >= session.getMaxInactiveInterval() * 1000L) {
                session.invalidate(); SecurityContextHolder.clearContext();
                responses.error(response, 401, "UNAUTHENTICATED", "Your session expired. Sign in to continue.");
                return;
            }
            boolean background = request.getRequestURI().matches("/api/integrations/targets/[^/]+/status")
                    && !"true".equals(request.getHeader("X-Loadpilot-Interactive"));
            if (!background) session.setAttribute(LAST_INTERACTIVE, now);
        }
        chain.doFilter(request, response);
    }
}
