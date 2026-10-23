package dev.loadpilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Use a dedicated native PostgreSQL database; never point tests at application data.
@SpringBootTest(properties={
        "platform.password=integration-test-password-123",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD}"
})
@AutoConfigureMockMvc
class FoundationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate database;
    @Test void applicationStartsWithDatabaseAndProtectedApi() throws Exception {
        assertThat(database.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class))
                .isEqualTo(1);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status").with(httpBasic("admin","incorrect-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status").with(httpBasic("admin","integration-test-password-123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("project-setup"));
    }
}
