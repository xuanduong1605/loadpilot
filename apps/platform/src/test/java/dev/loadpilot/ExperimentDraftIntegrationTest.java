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
class ExperimentDraftIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    ObjectNode input() {
        UUID target = UUID.randomUUID();
        db.update("INSERT INTO registered_target(id,name,base_url,environment,namespace,deployment,container) VALUES (?,?,'https://fixture.invalid','STAGING','fixture','api','api')", target, "Draft " + target);
        ObjectNode settings = json.createObjectNode().put("minimumRate", 1).put("maximumRate", 10)
                .put("durationSeconds", 60).put("warmupSeconds", 10).put("p95LatencyMs", 250)
                .put("maximumErrorRatio", 0.01).put("repetitions", 2).put("maximumRuns", 5).put("totalBudgetSeconds", 300);
        return json.createObjectNode().put("targetId", target.toString()).set("settings", settings);
    }
    @Test void savesReloadsUpdatesAndRejectsStaleRevision() throws Exception {
        var input = input();
        var response = mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(1)).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/experiments/drafts/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.maximumRate").value(10)).andExpect(jsonPath("$.targetId").value(input.get("targetId").asText()));
        input.put("revision", 1); ((ObjectNode) input.get("settings")).put("maximumRate", 20);
        mvc.perform(put("/api/experiments/drafts/" + id).with(csrf()).contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put("/api/experiments/drafts/" + id).with(csrf()).contentType("application/json").content(input.toString()))
                .andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT count(*) FROM access_audit WHERE object_id=?::uuid AND action='EXPERIMENT_DRAFT_SAVED'", Integer.class, id)).isEqualTo(2);
    }
    @Test void rejectsInvalidAndArchivedInputsWithoutPersisting() throws Exception {
        var input = input();
        ((ObjectNode) input.get("settings")).put("minimumRate", 50);
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Minimum rate must not exceed maximum rate."));
        ((ObjectNode) input.get("settings")).put("minimumRate", 1);
        db.update("UPDATE registered_target SET archived_at=CURRENT_TIMESTAMP WHERE id=?::uuid", input.get("targetId").asText());
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_draft WHERE target_id=?::uuid", Integer.class, input.get("targetId").asText())).isZero();
    }
    @Test void rejectsMissingFieldsAndCsrfAndUnknownDraft() throws Exception {
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/experiments/drafts").contentType("application/json").content(input().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/api/experiments/drafts/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
    @Test void executionBoundsRoundTripRejectsScalarAndPolicyBypasses() throws Exception {
        var input = input();
        var bounds = json.createObjectNode().put("precisionIterationsPerSecond", 1).put("preAllocatedVUs", 1).put("maximumVUs", 2)
                .put("workerCount", 1).put("generatorCpuMillicores", 250).put("generatorMemoryMiB", 128).put("requestTimeoutMs", 1000)
                .put("startupTimeoutSeconds", 5).put("gracefulStopSeconds", 2).put("maximumAttemptsPerRun", 1);
        input.set("executionBounds", bounds);
        bounds.put("workerCount", 2);
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        bounds.put("workerCount", 1).put("maximumVUs", 1.5);
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        bounds.put("maximumVUs", 2).put("maximumAttemptsPerRun", 3);
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
        bounds.put("maximumAttemptsPerRun", 1);
        var saved = mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.executionBounds.generatorMemoryMiB").value(128)).andReturn();
        String id = json.readTree(saved.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/experiments/drafts/" + id)).andExpect(jsonPath("$.executionBounds.requestTimeoutMs").value(1000));
        bounds.remove("workerCount");
        mvc.perform(post("/api/experiments/drafts").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
    }
}
