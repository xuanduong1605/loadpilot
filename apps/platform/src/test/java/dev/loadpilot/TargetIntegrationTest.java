package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.audit.TargetAudit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "platform.username=admin", "platform.password=integration-test-password-123",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}", "spring.datasource.password=${TEST_DATABASE_PASSWORD}"})
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = "OPERATOR")
@Transactional
class TargetIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @MockitoSpyBean TargetAudit audit;

    ObjectNode input(String name) {
        return json.createObjectNode().put("name", name).put("baseUrl", "https://fixture.example.invalid/api")
                .put("environment", "STAGING").put("namespace", "fixture").put("deployment", "fixture-api")
                .put("container", "api").put("credentialRef", "fixture-readonly").put("monitoringRef", "fixture-prometheus");
    }
    String create(ObjectNode input) throws Exception {
        var result = mvc.perform(post("/api/targets").with(csrf()).contentType("application/json").content(input.toString()))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.revision").value(1)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test void multipleSystemsRoundTripPaginateAndAuditWithoutConnectionPayloads() throws Exception {
        int initial = database.queryForObject("SELECT count(*) FROM registered_target WHERE archived_at IS NULL", Integer.class);
        String id = create(input("Target integration A"));
        create(input("Target integration B"));
        mvc.perform(get("/api/targets").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(initial + 2)).andExpect(jsonPath("$.limit").value(1));
        mvc.perform(get("/api/targets/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("https://fixture.example.invalid/api"))
                .andExpect(jsonPath("$.credentialRef").value("fixture-readonly"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        assertThat(database.queryForObject("SELECT count(*) FROM access_audit WHERE object_id=?::uuid AND actor='admin' AND action='TARGET_CREATED' AND object_type='TARGET'", Integer.class, id)).isEqualTo(1);
    }

    @Test void lostUpdatesAndArchivePreserveIdentityAndExcludeActiveList() throws Exception {
        int initial = database.queryForObject("SELECT count(*) FROM registered_target WHERE archived_at IS NULL", Integer.class);
        int all = database.queryForObject("SELECT count(*) FROM registered_target", Integer.class);
        String id = create(input("Target editable"));
        var edited = input("Target edited").put("revision", 1);
        mvc.perform(put("/api/targets/" + id).with(csrf()).contentType("application/json").content(edited.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put("/api/targets/" + id).with(csrf()).contentType("application/json").content(edited.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARGET_CONFLICT"));
        mvc.perform(post("/api/targets/" + id + "/archive").with(csrf()).contentType("application/json").content("{\"revision\":1}"))
                .andExpect(status().isConflict());
        for (int retry = 0; retry < 2; retry++)
            mvc.perform(post("/api/targets/" + id + "/archive").with(csrf()).contentType("application/json").content("{\"revision\":2}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3)).andExpect(jsonPath("$.archivedAt").isNotEmpty());
        mvc.perform(get("/api/targets")).andExpect(jsonPath("$.total").value(initial));
        mvc.perform(get("/api/targets").param("includeArchived", "true")).andExpect(jsonPath("$.total").value(all + 1));
        mvc.perform(get("/api/targets/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Target edited"));
        create(input("Target edited"));
        assertThat(database.queryForObject("SELECT count(*) FROM access_audit WHERE object_id=?::uuid AND action='TARGET_ARCHIVED'", Integer.class, id)).isEqualTo(1);
    }

    @Test void validationNeverEchoesCredentialUrlsOrAcceptsMalformedMetadata() throws Exception {
        for (String url : new String[]{"https://operator:private-password@example.invalid", "file:///private", "https://example.invalid?token=private", "https://example.invalid:70000", "not-a-url"}) {
            mvc.perform(post("/api/targets").with(csrf()).contentType("application/json").content(input("Invalid").put("baseUrl", url).toString()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-password"))));
        }
        mvc.perform(post("/api/targets").with(csrf()).contentType("application/json").content(input("Invalid").put("namespace", "Not Valid").toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/targets").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/targets/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/targets/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
        mvc.perform(post("/api/targets").contentType("application/json").content(input("Denied").toString()))
                .andExpect(status().isForbidden());
    }

    @Test void activeNamesAreCaseInsensitive() throws Exception {
        create(input("Unique name"));
        mvc.perform(post("/api/targets").with(csrf()).contentType("application/json").content(input("UNIQUE NAME").toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditFailureRollsBackMetadataInsteadOfReportingSuccess() throws Exception {
        doThrow(new DataAccessResourceFailureException("private-database-detail")).when(audit).record(anyString(), anyString(), any());
        mvc.perform(post("/api/targets").with(csrf()).contentType("application/json").content(input("Audit rollback test").toString()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TARGETS_UNAVAILABLE"));
        assertThat(database.queryForObject("SELECT count(*) FROM registered_target WHERE name='Audit rollback test'", Integer.class)).isZero();
    }
}
