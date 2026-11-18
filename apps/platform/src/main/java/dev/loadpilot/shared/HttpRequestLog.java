package dev.loadpilot.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Records completed backend requests, including requests rejected before MVC. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HttpRequestLog extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(HttpRequestLog.class);
    private static final String REQUEST_ID = "loadpilot.requestId";
    private static final String CODE = "loadpilot.errorCode";
    private static final String REASON = "loadpilot.errorReason";
    private static final String FAILURE_TYPE = "loadpilot.failureType";
    private static final String SQL_STATE = "loadpilot.sqlState";
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");
    private static final Set<String> SECURITY_ROUTES = Set.of("/api/access/login", "/api/access/logout");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        response.setHeader(HEADER, id);
        var previous = MDC.getCopyOfContextMap();
        MDC.put(REQUEST_ID, id);
        long started = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            failed = true;
            recordFailure(failure);
            throw failure;
        } finally {
            try {
                int status = failed && !response.isCommitted() ? 500 : response.getStatus();
                Level level = status >= 500 ? Level.ERROR : status >= 400 ? Level.WARN : Level.INFO;
                String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                log.atLevel(level).log("http_request request_id={} method={} route={} status={} duration_ms={} code={} reason={} failure_type={} sqlstate={}",
                        id, method, route(request), status, elapsed, field(CODE), field(REASON), field(FAILURE_TYPE), field(SQL_STATE));
            } finally {
                if (previous == null) MDC.clear();
                else MDC.setContextMap(previous);
            }
        }
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) return pattern.toString();
        for (String route : SECURITY_ROUTES) {
            if ((request.getContextPath() + route).equals(request.getRequestURI())) return route;
        }
        return "unmatched";
    }

    public static void recordErrorCode(String code) {
        recordMarker(CODE, code);
    }

    public static void recordReason(String reason) {
        recordMarker(REASON, reason);
    }

    private static void recordMarker(String key, String value) {
        if (MDC.get(REQUEST_ID) != null && value.matches("[A-Z][A-Z0-9_]{0,63}")) MDC.put(key, value);
    }

    public static void recordFailure(Throwable failure) {
        if (MDC.get(REQUEST_ID) == null) return;
        MDC.put(FAILURE_TYPE, failure.getClass().getSimpleName());
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[0-9A-Z]{5}")) {
                MDC.put(SQL_STATE, sql.getSQLState());
                return;
            }
        }
    }

    private static String field(String key) {
        String value = MDC.get(key);
        return value == null ? "-" : value;
    }
}
