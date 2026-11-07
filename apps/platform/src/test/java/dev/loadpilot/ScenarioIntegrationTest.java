package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.username=admin", "platform.password=integration-test-password-123",
        "platform.scenarios.node-executable=${TEST_NODE_EXECUTABLE:}", "platform.scenarios.validator-script=${TEST_VALIDATOR_SCRIPT:}",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}", "spring.datasource.password=${TEST_DATABASE_PASSWORD}"})
@AutoConfigureMockMvc
@WithMockUser(username="admin", roles="OPERATOR")
@Transactional
class ScenarioIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    static final String SOURCE = "// Xin chào\r\nexport default function () {}\n";
    ObjectNode input(String source) { return json.createObjectNode().put("name", "Scenario " + UUID.randomUUID()).put("source", source); }
    String create(ObjectNode input) throws Exception {
        var response = mvc.perform(post("/api/scenarios").with(csrf()).contentType("application/json").content(input.toString()))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.approved").value(false)).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("scenarioId").asText();
    }
    @Test void exactUtf8SourceHashAndOldVersionsSurviveEdits() throws Exception {
        var input = input(SOURCE);
        String id = create(input);
        mvc.perform(get("/api/scenarios/" + id + "/versions/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(SOURCE))
                .andExpect(jsonPath("$.sha256").value("be49eac520237d8637ea8c2c0a65129ecace444b2ca261757cc9dfc3300d956b"));
        input.put("source", "export default function () { /* edited */ }\n").put("expectedVersion", 1);
        mvc.perform(post("/api/scenarios/" + id + "/versions").with(csrf()).contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.approved").value(false));
        mvc.perform(get("/api/scenarios/" + id + "/versions/1")).andExpect(jsonPath("$.source").value(SOURCE));
        mvc.perform(get("/api/scenarios/" + id)).andExpect(jsonPath("$.version").value(2));
        assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE object_id=?::uuid AND action='SCENARIO_VERSION_SAVED'", Integer.class, id)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM scenario_version WHERE scenario_id=?::uuid", Integer.class, id)).isEqualTo(2);
    }
    @Test void staleVersionAndRenameCannotOverwriteHistory() throws Exception {
        var input = input(SOURCE); String id = create(input);
        input.put("expectedVersion", 1);
        mvc.perform(post("/api/scenarios/" + id + "/versions").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isOk());
        mvc.perform(post("/api/scenarios/" + id + "/versions").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isConflict());
        input.put("expectedVersion", 2).put("name", "renamed");
        mvc.perform(post("/api/scenarios/" + id + "/versions").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT latest_version FROM scenario WHERE id=?::uuid", Integer.class, id)).isEqualTo(2);
    }
    @Test void byteBoundsValidationAndPaginationAreEnforced() throws Exception {
        var input = input("á".repeat(32769));
        mvc.perform(post("/api/scenarios").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        input.put("source", " ");
        mvc.perform(post("/api/scenarios").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        input.put("source", "invalid\0source");
        mvc.perform(post("/api/scenarios").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        String id = create(input(SOURCE));
        mvc.perform(get("/api/scenarios").param("limit", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/scenarios").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/scenarios/" + id + "/versions/999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/scenarios/not-a-uuid")).andExpect(status().isBadRequest());
    }
    @Test void accessCsrfAndRequestBoundsAreEnforced() throws Exception {
        mvc.perform(get("/api/scenarios").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/scenarios").contentType("application/json").content(input(SOURCE).toString())).andExpect(status().isForbidden());
        mvc.perform(post("/api/scenarios").with(csrf()).contentType("application/json").content("x".repeat(524289))).andExpect(status().isPayloadTooLarge());
    }
    @Test void savedValidationBindsExactVersionAndHashWithoutApproving() throws Exception {
        var input = input(SOURCE); String id = create(input);
        var saved = json.readTree(mvc.perform(get("/api/scenarios/" + id)).andReturn().getResponse().getContentAsString());
        var request = json.createObjectNode().put("sha256", saved.get("sha256").asText());
        if (System.getenv("TEST_NODE_EXECUTABLE") == null || System.getenv("TEST_VALIDATOR_SCRIPT") == null) {
            mvc.perform(post("/api/scenarios/" + id + "/versions/1/validation").with(csrf()).contentType("application/json").content(request.toString())).andExpect(status().isServiceUnavailable());
            return;
        }
        var checked = mvc.perform(post("/api/scenarios/" + id + "/versions/1/validation").with(csrf()).contentType("application/json").content(request.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policyAccepted").value(true)).andExpect(jsonPath("$.runtimeCheckRequired").value(true)).andReturn();
        String validationId = json.readTree(checked.getResponse().getContentAsString()).get("id").asText();
        var approval = json.createObjectNode().put("validationId", validationId).put("sha256", saved.get("sha256").asText()).put("acknowledged", false);
        mvc.perform(post("/api/scenarios/" + id + "/versions/1/approval").with(csrf()).contentType("application/json").content(approval.toString())).andExpect(status().isBadRequest());
        approval.put("acknowledged", true);
        var approved = mvc.perform(post("/api/scenarios/" + id + "/versions/1/approval").with(csrf()).contentType("application/json").content(approval.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runtimeCheckRequired").value(true)).andReturn();
        String approvalId = json.readTree(approved.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/scenarios/" + id + "/versions/1/approval").with(csrf()).contentType("application/json").content(approval.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(approvalId));
        mvc.perform(get("/api/scenarios/" + id + "/versions/1")).andExpect(jsonPath("$.approved").value(true));
        request.put("sha256", "0".repeat(64));
        mvc.perform(post("/api/scenarios/" + id + "/versions/1/validation").with(csrf()).contentType("application/json").content(request.toString())).andExpect(status().isConflict());
        input.put("source", "export default function() { open('not-executed'); }").put("expectedVersion", 1);
        mvc.perform(post("/api/scenarios/" + id + "/versions").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isOk());
        mvc.perform(get("/api/scenarios/" + id + "/versions/1/validation")).andExpect(status().isOk()).andExpect(jsonPath("$.sha256").value(saved.get("sha256").asText()));
        mvc.perform(get("/api/scenarios/" + id + "/versions/2/validation")).andExpect(status().isNotFound());
        mvc.perform(get("/api/scenarios/" + id)).andExpect(jsonPath("$.approved").value(false));
        assertThat(db.queryForObject("SELECT count(*) FROM scenario_validation WHERE scenario_id=?::uuid", Integer.class, id)).isEqualTo(1);
        mvc.perform(post("/api/scenarios/" + id + "/versions/2/approval").with(csrf()).contentType("application/json").content(approval.toString())).andExpect(status().isConflict());
        var second = json.readTree(mvc.perform(get("/api/scenarios/" + id)).andReturn().getResponse().getContentAsString());
        request.put("sha256", second.get("sha256").asText());
        var denied = mvc.perform(post("/api/scenarios/" + id + "/versions/2/validation").with(csrf()).contentType("application/json").content(request.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policyAccepted").value(false)).andReturn();
        approval.put("sha256", second.get("sha256").asText()).put("validationId", json.readTree(denied.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/api/scenarios/" + id + "/versions/2/approval").with(csrf()).contentType("application/json").content(approval.toString())).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE object_id=?::uuid AND action='SCENARIO_APPROVED'", Integer.class, id)).isEqualTo(1);
    }
}
