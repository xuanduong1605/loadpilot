package dev.loadpilot.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded single-operator rolling window; never trusts a user-supplied proxy address. */
public class LoginThrottle extends OncePerRequestFilter {
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();
    private final AccessResponses responses;
    private final AccessAudit audit;
    private final LongSupplier clock;
    private boolean recorded;
    public LoginThrottle(AccessResponses responses, AccessAudit audit) { this(responses, audit, System::nanoTime); }
    public LoginThrottle(AccessResponses responses, AccessAudit audit, LongSupplier clock) {
        this.responses = responses; this.audit = audit; this.clock = clock;
    }

    private synchronized int allowance() {
        long now = clock.getAsLong();
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= 60_000_000_000L) attempts.removeFirst();
        if (attempts.size() >= 10) {
            if (recorded) return 2;
            recorded = true;
            return 1;
        }
        recorded = false;
        attempts.addLast(now);
        return 0;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/api/access/login".equals(request.getServletPath())) {
            int allowance = allowance();
            if (allowance != 0) {
                response.setHeader("Retry-After", "60");
                if (allowance != 1 || audit.record(response, null, "LOGIN", "THROTTLED"))
                    responses.error(response, 429, "LOGIN_THROTTLED", "Too many sign-in attempts. Try again in a minute.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
