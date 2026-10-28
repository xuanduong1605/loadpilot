package dev.loadpilot;

import dev.loadpilot.access.AccessResponses;
import dev.loadpilot.access.AccessAudit;
import dev.loadpilot.access.LoginThrottle;
import dev.loadpilot.access.IdleSessionFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    InMemoryUserDetailsManager users(@Value("${platform.username}") String username,
                                    @Value("${platform.password}") String password, PasswordEncoder encoder) {
        if (password.length() < 20) throw new IllegalArgumentException("PLATFORM_PASSWORD must be at least 20 characters");
        if (username.isBlank() || username.length() > 128) throw new IllegalArgumentException("Invalid PLATFORM_USER");
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("OPERATOR").build());
    }

    @Bean HttpSessionEventPublisher sessionEvents() { return new HttpSessionEventPublisher(); }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AccessResponses responses, AccessAudit audit) throws Exception {
        return http.requestCache(cache -> cache.disable())
                .addFilterAfter(new IdleSessionFilter(responses), org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/actuator/health", "/api/access/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/access/login").permitAll()
                        .anyRequest().hasRole("OPERATOR"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> responses.error(res, 401, "UNAUTHENTICATED", "Sign in to continue."))
                        .accessDeniedHandler((req, res, ex) -> responses.error(res, 403,
                                ex instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN", "Request not permitted.")))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId())
                        .maximumSessions(1).expiredSessionStrategy(event -> responses.error(event.getResponse(),
                                401, "UNAUTHENTICATED", "Sign in to continue.")))
                .addFilterBefore(new LoginThrottle(responses, audit), UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form.loginPage("/login/").loginProcessingUrl("/api/access/login")
                        .successHandler((req, res, auth) -> {
                            req.getSession().setAttribute(IdleSessionFilter.LAST_INTERACTIVE, System.currentTimeMillis());
                            if (audit.record(res, auth.getName(), "LOGIN", "SUCCESS"))
                                responses.identity(res, auth, req.getSession().getMaxInactiveInterval());
                            else {
                                req.getSession().invalidate();
                                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            }
                        })
                        .failureHandler((req, res, ex) -> {
                            if (audit.record(res, null, "LOGIN", "DENIED"))
                                responses.error(res, 401, "INVALID_CREDENTIALS", "Username or password is incorrect.");
                        }))
                .logout(logout -> logout.logoutUrl("/api/access/logout").deleteCookies("LOADPILOT_SESSION")
                        .logoutSuccessHandler((req, res, auth) -> {
                            if (audit.record(res, auth == null ? null : auth.getName(), "LOGOUT", "SUCCESS")) res.setStatus(204);
                        }))
                .build();
    }
}
