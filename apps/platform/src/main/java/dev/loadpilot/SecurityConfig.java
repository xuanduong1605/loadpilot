package dev.loadpilot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    InMemoryUserDetailsManager users(@Value("${platform.username}") String username,
                                    @Value("${platform.password}") String password,
                                    PasswordEncoder passwordEncoder) {
        if (password.length() < 20) throw new IllegalArgumentException("PLATFORM_PASSWORD must be at least 20 characters");
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password)).roles("OPERATOR").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                // A non-simple header prevents cross-origin form/Basic-auth CSRF.
                // No CORS policy is enabled, so another origin cannot pass preflight.
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                              FilterChain chain) throws ServletException, IOException {
                        if (!java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                                && !"web".equals(request.getHeader("X-Loadpilot-Client"))) {
                            response.sendError(403, "X-Loadpilot-Client header required"); return;
                        }
                        chain.doFilter(request, response);
                    }
                }, BasicAuthenticationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults()).build();
    }
}
