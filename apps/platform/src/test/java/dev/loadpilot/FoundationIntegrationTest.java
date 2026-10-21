package dev.loadpilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.password=integration-test-password-123"})
@AutoConfigureMockMvc
@Testcontainers
class FoundationIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Test void applicationStartsWithDatabaseAndProtectedApi() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status").with(httpBasic("admin","incorrect-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status").with(httpBasic("admin","integration-test-password-123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("project-setup"));
    }
}
