package dev.loadpilot.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Disabling an account or removing its operator role also revokes existing API access. */
public class AccountStatusFilter extends OncePerRequestFilter {
    private final UserDetailsService users;
    private final AccessResponses responses;
    public AccountStatusFilter(UserDetailsService users, AccessResponses responses) {
        this.users = users;
        this.responses = responses;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        // Logout must still revoke the local session when account storage is offline.
        return "POST".equals(request.getMethod())
                && (request.getContextPath() + "/api/access/logout").equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_OPERATOR".equals(a.getAuthority()))) {
            try {
                var account = users.loadUserByUsername(authentication.getName());
                if (!account.isEnabled() || account.getAuthorities().stream().noneMatch(a -> "ROLE_OPERATOR".equals(a.getAuthority()))) {
                    revoke(request, response);
                    return;
                }
            } catch (UsernameNotFoundException missing) {
                revoke(request, response);
                return;
            } catch (DataAccessException | AuthenticationServiceException unavailable) {
                responses.error(response, 503, "ACCESS_UNAVAILABLE", "Sign-in service is unavailable. Try again later.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void revoke(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        responses.error(response, 401, "UNAUTHENTICATED", "Sign in to continue.");
    }
}
