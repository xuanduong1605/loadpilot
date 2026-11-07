package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.audit.TargetAudit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.username=admin","platform.password=integration-test-password-123",
        "platform.connections.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "platform.connections.allowed-origins=https://127.0.0.1:18443", "platform.connections.allowed-addresses=127.0.0.1",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}","spring.datasource.password=${TEST_DATABASE_PASSWORD}"})
@AutoConfigureMockMvc
class SystemRegistrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @MockitoSpyBean TargetAudit audit;
    final String name = "Atomic fixture " + java.util.UUID.randomUUID();
    ObjectNode input() {
        var input = json.createObjectNode();
        input.putObject("system").put("name",name).put("baseUrl","http://127.0.0.1:18080/health").put("environment","STAGING")
                .put("namespace","fixture").put("deployment","fixture-api").put("container","api");
        input.putObject("connection").put("revision",0).put("targetRevision",1)
                .put("kubernetesUrl","https://127.0.0.1:18443").put("kubernetesCa","").put("kubernetesToken","fixture-readonly-token")
                .put("prometheusUrl","").put("prometheusCa","").put("prometheusToken","").put("readOnlyAcknowledged",true);
        return input;
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(ObjectNode input) {
        return post("/api/integrations/systems").with(user("admin").roles("OPERATOR")).with(csrf()).contentType("application/json").content(input.toString());
    }
    long count() { return database.queryForObject("SELECT count(*) FROM registered_target WHERE name=?", Long.class, name); }
    @AfterEach void cleanup() {
        var ids = database.queryForList("SELECT id FROM registered_target WHERE name=?", java.util.UUID.class, name);
        for (var id : ids) {
            database.update("DELETE FROM access_audit WHERE object_id=?",id);
            database.update("DELETE FROM system_connection WHERE target_id=?",id);
            database.update("DELETE FROM registered_target WHERE id=?",id);
        }
    }
    @Test void createsBoundEncryptedConnectionWithBothAuditsWithoutEchoingCredentials() throws Exception {
        String body = mvc.perform(request(input())).andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.revision").value(2)).andReturn().getResponse().getContentAsString();
        var result = json.readTree(body); var id = java.util.UUID.fromString(result.path("id").asText());
        assertThat(result.path("credentialRef").asText()).isEqualTo(id.toString());
        assertThat(body).doesNotContain("fixture-readonly-token");
        assertThat(database.queryForObject("SELECT kubernetes_token FROM system_connection WHERE target_id=?",String.class,id)).doesNotContain("fixture-readonly-token");
        assertThat(database.queryForList("SELECT action FROM access_audit WHERE object_id=?",String.class,id)).containsExactlyInAnyOrder("TARGET_CREATED","CONNECTION_SAVED");
        mvc.perform(get("/api/integrations/targets/"+id+"/connection").with(user("admin").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasKubernetesToken").value(true)).andExpect(jsonPath("$.kubernetesToken").doesNotExist());
    }
    @Test void failedConnectionAndFailedAuditLeaveNoPartlyRegisteredSystem() throws Exception {
        var invalid = input(); ((ObjectNode)invalid.get("connection")).put("kubernetesCa","private-invalid-ca");
        mvc.perform(request(invalid)).andExpect(status().isBadRequest()); assertThat(count()).isZero();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private-database-detail"))
                .when(audit).record(anyString(),eq("CONNECTION_SAVED"),any(java.util.UUID.class));
        mvc.perform(request(input())).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-database-detail"))));
        assertThat(count()).isZero();
    }
    @Test void policyAndNestedValidationCannotBeBypassedThroughCombinedRegistration() throws Exception {
        var input = input(); ((ObjectNode)input.get("connection")).put("kubernetesUrl","https://unrelated.invalid");
        mvc.perform(request(input)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DESTINATION_DENIED"));
        input = input(); ((ObjectNode)input.get("connection")).put("readOnlyAcknowledged",false);
        mvc.perform(request(input)).andExpect(status().isBadRequest());
        input = input(); ((ObjectNode)input.get("system")).put("namespace","INVALID");
        mvc.perform(request(input)).andExpect(status().isBadRequest());
        input = input(); ((ObjectNode)input.get("connection")).put("revision",1);
        mvc.perform(request(input)).andExpect(status().isBadRequest());
        input = input(); ((ObjectNode)input.get("system")).put("credentialRef","unrelated-credentials");
        mvc.perform(request(input)).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }
    @Test void transportSessionCsrfAndBodyLimitsApplyBeforeAnyAccountOrProfileWrite() throws Exception {
        mvc.perform(request(input()).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/integrations/systems").with(user("admin").roles("OPERATOR")).contentType("application/json").content(input().toString())).andExpect(status().isForbidden());
        mvc.perform(request(input()).with(r->{r.setServerName("public.example.invalid");return r;}))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SECURE_TRANSPORT_REQUIRED"));
        mvc.perform(request(input()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/integrations/systems").with(user("admin").roles("OPERATOR")).with(csrf()).contentType("application/json").content("x".repeat(32769)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(count()).isZero();
    }
    @Test void duplicateNameIsAnActionableConflictWithoutAnotherConnection() throws Exception {
        mvc.perform(request(input())).andExpect(status().isCreated());
        mvc.perform(request(input())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARGET_CONFLICT"));
        assertThat(count()).isEqualTo(1);
    }
}
