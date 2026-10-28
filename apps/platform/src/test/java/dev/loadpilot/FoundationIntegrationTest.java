package dev.loadpilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Use a dedicated native PostgreSQL database; never point tests at application data.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "platform.username=admin",
        "platform.password=integration-test-password-123",
        "server.servlet.session.cookie.secure=true",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD}"
})
@AutoConfigureMockMvc
class FoundationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate database;
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @Test void tlsDeploymentCookieHasRequiredFlagsOnRealServletResponse() {
        var response = http.getForEntity("/api/access/csrf", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .contains("LOADPILOT_SESSION=", "Path=/", "HttpOnly", "SameSite=Lax", "Secure");
    }
    @Test void applicationStartsWithDatabaseAndProtectedApi() throws Exception {
        assertThat(database.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class))
                .isEqualTo(1);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status").with(httpBasic("admin","incorrect-password")))
                .andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/access/login").with(csrf())
                        .param("username", "admin").param("password", "integration-test-password-123"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/status").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("project-setup"));
        assertThat(database.queryForObject("SELECT count(*) FROM access_audit WHERE actor='admin' AND action='LOGIN' AND outcome='SUCCESS'",
                Integer.class)).isGreaterThan(0);
    }
}
