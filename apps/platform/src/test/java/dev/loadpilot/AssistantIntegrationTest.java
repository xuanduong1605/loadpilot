package dev.loadpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.ai.AssistantApi.AnalysisRequest;
import dev.loadpilot.ai.AssistantApi.Message;
import dev.loadpilot.ai.AssistantApi.Task;
import dev.loadpilot.ai.AssistantProvider;
import dev.loadpilot.experiments.RunPartialResults;
import dev.loadpilot.experiments.RunResults;
import dev.loadpilot.experiments.ExperimentSettingsApi.Settings;
import dev.loadpilot.experiments.ExperimentSnapshots;
import dev.loadpilot.experiments.ExecutionBounds;
import dev.loadpilot.experiments.LoadProfile;
import dev.loadpilot.experiments.SloPolicy;
import dev.loadpilot.integrations.k6.K6LiveHttpMetrics;
import dev.loadpilot.integrations.k6.K6TerminalSummary;
import dev.loadpilot.scenarios.ScenarioApi;
import dev.loadpilot.scenarios.ScenarioStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "platform.username=admin", "platform.password=integration-test-password-123",
        "GEMINI_API_KEY=fixture-gemini-credential",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}", "spring.datasource.password=${TEST_DATABASE_PASSWORD}"})
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = "OPERATOR")
@Transactional
class AssistantIntegrationTest {
    private static final String CHATS = "/api/assistant/conversations";
    private static final String SETTINGS_DRAFT = "/api/assistant/settings-draft";
    private static final String SCRIPT_DRAFT = "/api/assistant/script-draft";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoBean AssistantProvider provider;
    @MockitoSpyBean RunResults runResults;
    @MockitoSpyBean ExperimentSnapshots.Store snapshots;

    @Test void savesAndReplaysChatWhileRestrictingHistoryToTheSignedInOperator() throws Exception {
        UUID id = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        var faq = new Message(request, 0, "Xin chào! How do I approve a script?", Task.ASK, null);
        mvc.perform(send(id, faq)).andExpect(status().isOk()).andExpect(jsonPath("$.summary.turnCount").value(1))
                .andExpect(jsonPath("$.turn.reply.links[0].destination").value("SCRIPTS"));
        mvc.perform(send(id, faq)).andExpect(status().isOk()).andExpect(jsonPath("$.turn.requestId").value(request.toString()));
        mvc.perform(send(id, new Message(request, 0, "Different question", Task.ASK, null))).andExpect(status().isConflict());
        mvc.perform(send(id, new Message(UUID.randomUUID(), 0, "Old tab", Task.ASK, null))).andExpect(status().isConflict());
        mvc.perform(get(CHATS + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.turns.length()").value(1))
                .andExpect(jsonPath("$.turns[0].text").value(faq.text()));
        mvc.perform(get(CHATS)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id.toString()));

        addOtherOperator();
        mvc.perform(get(CHATS).with(user("other-operator").roles("OPERATOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(CHATS + "/" + id).with(user("other-operator").roles("OPERATOR"))).andExpect(status().isNotFound());
        mvc.perform(send(id, faq).with(user("other-operator").roles("OPERATOR"))).andExpect(status().isNotFound());
        mvc.perform(get(CHATS).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(CHATS).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        verifyNoInteractions(provider);
    }

    @Test void filtersCredentialsAndKeepsDeterministicRepliesOnProviderFailure() throws Exception {
        UUID id = UUID.randomUUID();
        var secret = new Message(UUID.randomUUID(), 0, "password=synthetic-example", Task.SCRIPT, null);
        mvc.perform(send(id, secret)).andExpect(status().isBadRequest());
        mvc.perform(send(id, new Message(UUID.randomUUID(), 0, "Please check fixture-gemini-credential", Task.SCRIPT, null)))
                .andExpect(status().isBadRequest());
        mvc.perform(send(id, new Message(UUID.randomUUID(), 0, "AIza" + "x".repeat(35), Task.ASK, null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(CHATS + "/" + id + "/messages").contentType("application/json").content(json.writeValueAsString(secret)))
                .andExpect(status().isForbidden());
        mvc.perform(get(CHATS + "/" + id)).andExpect(status().isNotFound());
        verifyNoInteractions(provider);
        when(provider.advise(any())).thenReturn("Review the response check before approving.", "Bearer synthetic-fixture-token")
                .thenThrow(new IllegalStateException("provider offline"));

        var question = new Message(UUID.randomUUID(), 0, "Help me modify the script.", Task.SCRIPT, null);
        mvc.perform(send(id, question)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.suggestion").value("Review the response check before approving."));
        mvc.perform(send(id, question)).andExpect(status().isOk());
        verify(provider, times(1)).advise(any());
        mvc.perform(send(id, new Message(UUID.randomUUID(), 1, "What else should I check?", Task.SCRIPT, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.reply.suggestion").doesNotExist())
                .andExpect(jsonPath("$.turn.reply.providerUnavailable").value(true));
        mvc.perform(send(id, new Message(UUID.randomUUID(), 2, "How could I revise it?", Task.SCRIPT, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.reply.providerUnavailable").value(true))
                .andExpect(jsonPath("$.turn.reply.links[1].destination").value("SCRIPT_EDITOR"));
        verify(provider, times(2)).advise(argThat(context -> context.recent().size() == 1
                && context.recent().getFirst().question().equals(question.text())));
    }

    @ParameterizedTest
    @CsvSource({
            "'What is the warm-up and measurement window for a ramp?', 'constant peak plateau', SETUP",
            "'What is the difference between requested load and requests/s?', 'An iteration can make multiple HTTP requests', SETUP",
            "'Do repetitions count toward the time budget?', 'startup, run duration including warm-up, shutdown, and configured retries', SETUP",
            "'Can I reuse finalized inputs with different settings?', 'Earlier runs retain their original inputs', SETUP",
            "'Can a whole-run summary supply the SLO verdict?', 'A completed run can still have an unevaluated SLO', RUNS"
    })
    void answersOperationalFaqsLocally(String question, String text, String destination) throws Exception {
        mvc.perform(send(UUID.randomUUID(), new Message(UUID.randomUUID(), 0, question, Task.ASK, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.text", containsString(text)))
                .andExpect(jsonPath("$.turn.reply.links[0].destination").value(destination))
                .andExpect(jsonPath("$.turn.reply.suggestion").doesNotExist())
                .andExpect(jsonPath("$.turn.reply.providerUnavailable").value(false));
        verifyNoInteractions(provider);
    }

    @Test void answersNaturalLanguageExamplesAndKeepsUnscopedAdviceFromOlderChats() throws Exception {
        UUID conversation = UUID.randomUUID();
        String oldQuestion = "Help me revise a k6 scenario.";
        String question = "For a fictional k6 ramp test, give a short example of separating warm-up from the measurement window.";
        when(provider.advise(any())).thenReturn("Use finite phases.", "Measure the constant-rate phase separately.", "Check for HTTP 200.");

        mvc.perform(send(conversation, new Message(UUID.randomUUID(), 0, oldQuestion, Task.SCRIPT, null)))
                .andExpect(status().isOk());
        mvc.perform(send(conversation, new Message(UUID.randomUUID(), 1, question, Task.ASK, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.suggestion").value("Measure the constant-rate phase separately."))
                .andExpect(jsonPath("$.turn.reply.links[0].destination").value("SCRIPT_EDITOR"));
        verify(provider).advise(argThat(request -> request.question().equals(question) && request.recent().size() == 1
                && request.recent().getFirst().question().equals(oldQuestion)));

        mvc.perform(send(conversation, new Message(UUID.randomUUID(), 2, "How do I check HTTP status in k6?", Task.ASK, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.suggestion").value("Check for HTTP 200."));
    }

    @Test void previewsStructuredSettingsWithoutChangingSavedInputs() throws Exception {
        UUID run = completedRun();
        db.update("""
                INSERT INTO experiment_draft(id,target_id,settings,created_by)
                VALUES (?,?,'{"maximumRate":10}','admin')
                """, UUID.randomUUID(), targetId(run));
        long turnsBefore = db.queryForObject("SELECT count(*) FROM assistant_turn", Long.class);
        String goal = "Raise the maximum rate to 20 iterations/s and set p99 to 175 ms.";
        when(provider.proposeSettings(eq(goal), any())).thenReturn("""
                {"changes":[{"field":"maximumRate","value":20},{"field":"latencyPercentile","value":99},
                {"field":"latencyThresholdMs","value":175}]}
                """);
        mvc.perform(settingsDraft(goal)).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(3))
                .andExpect(jsonPath("$.preview.valid").value(true))
                .andExpect(jsonPath("$.preview.settings.maximumRate").value(20))
                .andExpect(jsonPath("$.preview.settings.minimumRate").value(1))
                .andExpect(jsonPath("$.preview.settings.p95LatencyMs").value(175))
                .andExpect(jsonPath("$.preview.sloPolicy.latencyPercentile").value("P99"))
                .andExpect(jsonPath("$.preview.sloPolicy.latencyThresholdMs").value(175))
                .andExpect(jsonPath("$.preview.executionBounds.generatorMemoryMiB").value(128));
        assertThat(db.queryForObject("""
                SELECT draft.settings FROM experiment_run run
                JOIN experiment_input_snapshot snapshot ON snapshot.id = run.snapshot_id
                JOIN experiment_draft draft ON draft.id = snapshot.draft_id
                WHERE run.id = ?
                """, String.class, run)).isEqualTo("{}");
        assertThat(db.queryForObject("SELECT status FROM experiment_run WHERE id = ?", String.class, run)).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("SELECT count(*) FROM assistant_turn", Long.class)).isEqualTo(turnsBefore);
        verify(provider).proposeSettings(eq(goal), argThat(schema -> schema.at("/properties/changes/items/properties/field/enum")
                .toString().contains("maximumRate")));
    }

    @Test void appliesManualBeanAndCrossFieldValidationToSettingsProposals() throws Exception {
        String goal = "Suggest revised bounds";
        when(provider.proposeSettings(eq(goal), any())).thenReturn(
                "{\"changes\":[{\"field\":\"maximumVUs\",\"value\":0}]}",
                "{\"changes\":[{\"field\":\"warmupSeconds\",\"value\":25}]}",
                "{\"changes\":[]}");
        mvc.perform(settingsDraft(goal)).andExpect(status().isOk()).andExpect(jsonPath("$.preview").doesNotExist())
                .andExpect(jsonPath("$.errors[0]", containsString("executionBounds.maximumVUs")));
        mvc.perform(settingsDraft(goal)).andExpect(status().isOk()).andExpect(jsonPath("$.preview").doesNotExist())
                .andExpect(jsonPath("$.errors[0]", containsString("measurement window")));
        mvc.perform(settingsDraft(goal)).andExpect(status().isOk()).andExpect(jsonPath("$.preview").doesNotExist())
                .andExpect(jsonPath("$.errors[0]", containsString("Specify the setting")));
    }

    @Test void previewsDurationUsingTheSameDerivedStageAsTheSteadyRunForm() throws Exception {
        String goal = "Set the duration to 40 seconds.";
        when(provider.proposeSettings(eq(goal), any())).thenReturn("""
                {"changes":[{"field":"durationSeconds","value":40}]}
                """);
        var current = json.readTree("""
                {"settings":{"minimumRate":1,"maximumRate":10,"durationSeconds":30,"warmupSeconds":5,
                "p95LatencyMs":150,"maximumErrorRatio":0.01,"repetitions":1,"maximumRuns":2,"totalBudgetSeconds":120},
                "executionBounds":{"precisionIterationsPerSecond":null,"preAllocatedVUs":2,"maximumVUs":10,"workerCount":1,
                "generatorCpuMillicores":250,"generatorMemoryMiB":128,"requestTimeoutMs":500,"startupTimeoutSeconds":10,
                "gracefulStopSeconds":5,"maximumAttemptsPerRun":1},
                "loadProfile":{"mode":"SINGLE_RUN","shape":"STEADY","startRate":5,"stages":[{"durationSeconds":30,"targetRate":5}],"measurementSeconds":25},
                "sloPolicy":{"latencyPercentile":"P95","latencyThresholdMs":150,"maximumErrorRatio":0.01,
                "successDefinition":"HTTP_200_399_WITHOUT_TRANSPORT_ERROR","resourceConditions":[]}}
                """);
        mvc.perform(settingsDraft(json.createObjectNode().put("goal", goal).set("current", current)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preview.valid").value(true))
                .andExpect(jsonPath("$.preview.loadProfile.stages[0].durationSeconds").value(40))
                .andExpect(jsonPath("$.preview.loadProfile.measurementSeconds").value(25));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "STEADY|5|30|[{\"durationSeconds\":30,\"targetRate\":5}]",
            "RAMP|1|30|[{\"durationSeconds\":5,\"targetRate\":5},{\"durationSeconds\":25,\"targetRate\":5}]",
            "SPIKE|1|35|[{\"durationSeconds\":5,\"targetRate\":5},{\"durationSeconds\":25,\"targetRate\":5},{\"durationSeconds\":5,\"targetRate\":1}]"
    })
    void draftsExplicitLoadProfilesForBlankAndCompleteForms(String shape, int start, int duration, String stages) throws Exception {
        String goal = "Draft the specified " + shape + " profile.";
        var output = json.createObjectNode();
        output.putArray("changes");
        output.putObject("profile").put("shape", shape).put("startRate", start).put("warmupSeconds", 5)
                .put("measurementSeconds", 25).set("stages", json.readTree(stages));
        when(provider.proposeSettings(eq(goal), any())).thenReturn(output.toString());
        mvc.perform(settingsDraft(json.createObjectNode().put("goal", goal))).andExpect(status().isOk())
                .andExpect(jsonPath("$.errors.length()").value(0)).andExpect(jsonPath("$.preview").doesNotExist())
                .andExpect(jsonPath("$.loadProfile.mode").value("SINGLE_RUN")).andExpect(jsonPath("$.loadProfile.shape").value(shape))
                .andExpect(jsonPath("$.loadProfile.startRate").value(start))
                .andExpect(jsonPath("$.changes[0].field").value("durationSeconds"))
                .andExpect(jsonPath("$.changes[0].value").value(duration));
        mvc.perform(settingsDraft(goal)).andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.valid").value(true))
                .andExpect(jsonPath("$.preview.settings.durationSeconds").value(duration))
                .andExpect(jsonPath("$.preview.settings.maximumRate").value(10))
                .andExpect(jsonPath("$.preview.executionBounds.precisionIterationsPerSecond").doesNotExist())
                .andExpect(jsonPath("$.preview.loadProfile.shape").value(shape))
                .andExpect(jsonPath("$.preview.loadProfile.measurementSeconds").value(25));
    }

    @Test void refusesLoadProfilesWithMisalignedMeasurementOrContradictoryTiming() throws Exception {
        String goal = "Plan a ramp.";
        String response = """
                {"changes":[],"profile":{"shape":"RAMP","startRate":1,"warmupSeconds":1,"measurementSeconds":25,
                "stages":[{"durationSeconds":5,"targetRate":5},{"durationSeconds":25,"targetRate":5}]}}
                """;
        when(provider.proposeSettings(eq(goal), any())).thenReturn(response,
                response.replace("\"changes\":[]", "\"changes\":[{\"field\":\"durationSeconds\",\"value\":40}]"),
                response.replace("\"targetRate\":5", "\"targetRate\":5.5"));
        var blank = json.createObjectNode().put("goal", goal);
        mvc.perform(settingsDraft(blank)).andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0]", containsString("constant positive peak-rate plateau")))
                .andExpect(jsonPath("$.loadProfile").doesNotExist());
        mvc.perform(settingsDraft(blank)).andExpect(status().isBadGateway());
        mvc.perform(settingsDraft(blank)).andExpect(status().isBadGateway());
    }

    @Test void proposesOnlyMentionedFieldsWithoutACompletedSettingsForm() throws Exception {
        long turnsBefore = db.queryForObject("SELECT count(*) FROM assistant_turn", Long.class);
        String goal = "Use at most 20 iterations/s and a 2% error ratio.";
        when(provider.proposeSettings(eq(goal), any())).thenReturn("""
                {"changes":[{"field":"maximumRate","value":20},{"field":"maximumErrorRatio","value":0.02}]}
                """);
        mvc.perform(settingsDraft(json.createObjectNode().put("goal", goal))).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(2))
                .andExpect(jsonPath("$.changes[0].field").value("maximumRate"))
                .andExpect(jsonPath("$.changes[1].value").value(0.02))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.preview").doesNotExist());
        assertThat(db.queryForObject("SELECT count(*) FROM assistant_turn", Long.class)).isEqualTo(turnsBefore);
    }

    @Test void checksFieldBoundsWithoutACompletedSettingsForm() throws Exception {
        String goal = "Preview a generator with too much memory.";
        when(provider.proposeSettings(eq(goal), any())).thenReturn("""
                {"changes":[{"field":"generatorMemoryMiB","value":4097}]}
                """);
        var request = json.createObjectNode().put("goal", goal).putNull("current");
        mvc.perform(settingsDraft(request)).andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").doesNotExist())
                .andExpect(jsonPath("$.errors[0]", containsString("executionBounds.generatorMemoryMiB")));
    }

    @Test void draftsAReadOnlyScriptThroughBothExistingValidatorsWithoutSaving() throws Exception {
        UUID target = scriptTarget(false);
        String goal = "Make one GET and check for status 204.";
        when(provider.proposeScript(eq(goal), any())).thenReturn("{\"method\":\"GET\",\"expectedStatus\":204}");
        Integer scripts = db.queryForObject("SELECT count(*) FROM scenario_version", Integer.class);
        mvc.perform(scriptDraft(goal, target, 750)).andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.expectedStatus").value(204))
                .andExpect(jsonPath("$.source", containsString("http.get(\"https://draft-fixture.invalid/health\", { timeout: 750, redirects: 0 })")))
                .andExpect(jsonPath("$.source", containsString("r.status === 204")))
                .andExpect(jsonPath("$.review.policyVersion").value("static-review-v1"))
                .andExpect(jsonPath("$.review.policyAccepted").value(true))
                .andExpect(jsonPath("$.executionReview.policyVersion").value("literal-http-v1"))
                .andExpect(jsonPath("$.executionReview.policyAccepted").value(true));
        assertThat(db.queryForObject("SELECT count(*) FROM scenario_version", Integer.class)).isEqualTo(scripts);
        verify(provider).proposeScript(eq(goal), argThat(schema -> !schema.toString().contains("draft-fixture.invalid")));

        String head = "Check the response with HEAD.";
        when(provider.proposeScript(eq(head), any())).thenReturn("{\"method\":\"HEAD\",\"expectedStatus\":null}");
        mvc.perform(scriptDraft(head, target, 800)).andExpect(status().isOk())
                .andExpect(jsonPath("$.source", containsString("http.head")))
                .andExpect(jsonPath("$.source", containsString("r.status >= 200 && r.status < 400")))
                .andExpect(jsonPath("$.executionReview.policyAccepted").value(true));
    }

    @Test void checksScriptDraftScopeAndRejectsUnusableProviderOutput() throws Exception {
        UUID target = scriptTarget(false);
        String goal = "Draft a script.";
        mvc.perform(scriptDraft(goal, UUID.randomUUID(), 750)).andExpect(status().isNotFound());
        mvc.perform(scriptDraft(goal, scriptTarget(true), 750)).andExpect(status().isConflict());
        mvc.perform(scriptDraft("GET https://private-fixture.invalid", target, 750)).andExpect(status().isBadRequest());
        mvc.perform(scriptDraft(goal, target, 750.5)).andExpect(status().isBadRequest());
        mvc.perform(scriptDraft(goal, target, 750).with(anonymous())).andExpect(status().isUnauthorized());
        verifyNoInteractions(provider);
        when(provider.proposeScript(eq(goal), any())).thenReturn(
                "{\"method\":\"UNSUPPORTED\",\"expectedStatus\":null}",
                "{\"method\":\"GET\",\"expectedStatus\":200,\"source\":\"unsupported source\"}",
                "{\"method\":\"POST\",\"expectedStatus\":200}",
                "{\"method\":\"HEAD\",\"expectedStatus\":204.5}").thenThrow(new IllegalStateException("provider offline"));
        mvc.perform(scriptDraft(goal, target, 750)).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").doesNotExist()).andExpect(jsonPath("$.errors[0]", containsString("GET or HEAD")));
        for (int failure = 0; failure < 3; failure++) mvc.perform(scriptDraft(goal, target, 750)).andExpect(status().isBadGateway());
        mvc.perform(scriptDraft(goal, target, 750)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("Continue editing manually")));
    }

    @Test void previewsOrderedReadOnlyChecksOnLocallyEnteredPaths() throws Exception {
        UUID target = scriptTarget(false);
        String goal = "HEAD the first path expecting 204, then GET the second expecting 200.";
        var paths = List.of("/catalog/status", "/catalog/items");
        when(provider.proposeScript(eq(goal), any())).thenReturn("""
                {"steps":[{"method":"HEAD","expectedStatus":204},{"method":"GET","expectedStatus":200}]}
                """);
        long scripts = db.queryForObject("SELECT count(*) FROM scenario_version", Long.class);
        var response = mvc.perform(scriptDraft(goal, target, 750, paths)).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].path").value("/catalog/status"))
                .andExpect(jsonPath("$.steps[0].method").value("HEAD"))
                .andExpect(jsonPath("$.steps[1].path").value("/catalog/items"))
                .andExpect(jsonPath("$.steps[1].expectedStatus").value(200))
                .andExpect(jsonPath("$.review.policyAccepted").value(true))
                .andExpect(jsonPath("$.executionReview.policyAccepted").value(true)).andReturn().getResponse();
        String source = json.readTree(response.getContentAsString()).path("source").asText();
        assertThat(source).contains(
                "http.head(\"https://draft-fixture.invalid/catalog/status\", { timeout: 750, redirects: 0 })",
                "r.status === 204", "http.get(\"https://draft-fixture.invalid/catalog/items\", { timeout: 750, redirects: 0 })", "r.status === 200");
        assertThat(source.indexOf("http.head")).isLessThan(source.indexOf("http.get"));
        assertThat(db.queryForObject("SELECT count(*) FROM scenario_version", Long.class)).isEqualTo(scripts);
        verify(provider).proposeScript(eq(goal), argThat(schema -> schema.at("/properties/steps/maxItems").asInt() == 2
                && !schema.toString().contains("catalog") && !schema.toString().contains("draft-fixture.invalid")));
    }

    @Test void rejectsForeignOrAmbiguousPathsAndRequiresACompleteOrderedDraft() throws Exception {
        UUID target = scriptTarget(false);
        String goal = "Draft ordered checks.";
        for (var path : List.of("https://other.invalid/", "//other.invalid/", "/health?private=value", "/health#fragment",
                "/%2fother.invalid/", "/health/../secret", "/encoded%5cpath")) {
            mvc.perform(scriptDraft(goal, target, 750, List.of(path))).andExpect(status().isBadRequest());
        }
        mvc.perform(scriptDraft(goal, target, 750, List.of("/a", "/b", "/c", "/d", "/e"))).andExpect(status().isBadRequest());
        verifyNoInteractions(provider);
        var paths = List.of("/health", "/catalog");
        when(provider.proposeScript(eq(goal), any())).thenReturn("{\"steps\":[]}", """
                {"steps":[{"method":"GET","expectedStatus":200}]}
                """, """
                {"steps":[{"method":"GET","expectedStatus":200},{"method":"HEAD","expectedStatus":null,"path":"/other"}]}
                """);
        mvc.perform(scriptDraft(goal, target, 750, paths)).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").doesNotExist()).andExpect(jsonPath("$.errors[0]", containsString("each path in order")));
        mvc.perform(scriptDraft(goal, target, 750, paths)).andExpect(status().isBadGateway());
        mvc.perform(scriptDraft(goal, target, 750, paths)).andExpect(status().isBadGateway());
    }

    private MockHttpServletRequestBuilder scriptDraft(String goal, UUID target, Number timeout) throws Exception {
        return scriptDraft(goal, target, timeout, null);
    }

    private MockHttpServletRequestBuilder scriptDraft(String goal, UUID target, Number timeout, List<String> paths) throws Exception {
        var body = json.createObjectNode().put("goal", goal).put("targetId", target.toString());
        body.set("requestTimeoutMs", json.valueToTree(timeout));
        if (paths != null) body.set("paths", json.valueToTree(paths));
        return post(SCRIPT_DRAFT).with(csrf()).contentType("application/json").content(json.writeValueAsString(body));
    }

    private UUID scriptTarget(boolean archived) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO registered_target(id,name,base_url,environment,namespace,deployment,container,archived_at)
                VALUES (?,?,'https://draft-fixture.invalid/health','STAGING','fixture','api','api',CASE WHEN ? THEN now() ELSE NULL END)
                """, id, "Script draft fixture " + id, archived);
        return id;
    }

    @Test void rejectsUnsafeProposalShapesAndPreservesManualEditingOnProviderFailure() throws Exception {
        String goal = "Suggest revised bounds";
        mvc.perform(settingsDraft("password=synthetic-example")).andExpect(status().isBadRequest());
        mvc.perform(settingsDraft(goal).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post(SETTINGS_DRAFT).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(provider);
        when(provider.proposeSettings(eq(goal), any())).thenReturn(
                "{\"changes\":[{\"field\":\"targetId\",\"value\":1}]}",
                "{\"changes\":[{\"field\":\"maximumRate\",\"value\":2.5}]}",
                "{\"changes\":[{\"field\":\"maximumRate\",\"value\":2},{\"field\":\"maximumRate\",\"value\":3}]}",
                "{\"changes\":[],\"changes\":[]}", "{\"changes\":[]}{}").thenThrow(new IllegalStateException("provider offline"));
        for (int attempt = 0; attempt < 5; attempt++) mvc.perform(settingsDraft(goal)).andExpect(status().isBadGateway());
        mvc.perform(settingsDraft(goal)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("Continue editing manually")));
    }

    private MockHttpServletRequestBuilder settingsDraft(String goal) throws Exception {
        var input = json.createObjectNode().put("goal", goal);
        input.set("current", json.readTree("""
                {"settings":{"minimumRate":1,"maximumRate":10,"durationSeconds":30,"warmupSeconds":5,
                "p95LatencyMs":150,"maximumErrorRatio":0.01,"repetitions":1,"maximumRuns":2,"totalBudgetSeconds":120},
                "executionBounds":{"precisionIterationsPerSecond":1,"preAllocatedVUs":2,"maximumVUs":10,"workerCount":1,
                "generatorCpuMillicores":250,"generatorMemoryMiB":128,"requestTimeoutMs":500,"startupTimeoutSeconds":10,
                "gracefulStopSeconds":5,"maximumAttemptsPerRun":1},
                "loadProfile":{"mode":"ADAPTIVE_LOAD","shape":"STEADY","stages":[],"measurementSeconds":25},
                "sloPolicy":{"latencyPercentile":"P95","latencyThresholdMs":150,"maximumErrorRatio":0.01,
                "successDefinition":"HTTP_200_399_WITHOUT_TRANSPORT_ERROR","resourceConditions":[]}}
                """));
        return settingsDraft(input);
    }

    private MockHttpServletRequestBuilder settingsDraft(JsonNode input) throws Exception {
        return post(SETTINGS_DRAFT).with(csrf()).contentType("application/json").content(json.writeValueAsString(input));
    }

    @Test void readsAnOwnedRunAndKeepsMissingEvidenceAndRunStartSeparate() throws Exception {
        UUID id = UUID.randomUUID();
        UUID run = completedRun();
        mvc.perform(send(id, new Message(UUID.randomUUID(), 0, "What is the status of my latest run?", Task.ASK, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.reply.text", containsString("completed")))
                .andExpect(jsonPath("$.turn.reply.links[0].recordId").value(run.toString()));
        mvc.perform(send(id, new Message(UUID.randomUUID(), 1, "Analyze results for " + run, Task.ASK, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.reply.text", containsString("insufficient recorded evidence")))
                .andExpect(jsonPath("$.turn.reply.links[0].destination").value("RUN_EVIDENCE"));
        mvc.perform(send(id, new Message(UUID.randomUUID(), 2, "Run a test", Task.START, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.reply.links[0].destination").value("INPUTS"));
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_run WHERE status <> 'COMPLETED' AND id = ?", Integer.class, run)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM experiment_run WHERE snapshot_id = (SELECT snapshot_id FROM experiment_run WHERE id = ?)", Integer.class, run)).isOne();
        addOtherOperator();
        mvc.perform(send(UUID.randomUUID(), new Message(UUID.randomUUID(), 0, "Show run status", Task.STATUS, run))
                .with(user("other-operator").roles("OPERATOR"))).andExpect(status().isNotFound());
        verifyNoInteractions(provider);
    }

    @Test void resolvesOnlyUniqueOwnedRunNamesAndClarifiesAmbiguity() throws Exception {
        UUID named = completedRun(), unrelated = completedRun();
        setRunName(named, "Checkout Nightly");
        var question = new Message(UUID.randomUUID(), 0, "What is the test status for Checkout Nightly?", Task.ASK, null);
        mvc.perform(send(UUID.randomUUID(), question)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.links[0].recordId").value(named.toString()));
        setRunName(unrelated, "Checkout Nightly");
        mvc.perform(send(UUID.randomUUID(), question)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.text", containsString("Which run do you mean?")))
                .andExpect(jsonPath("$.turn.reply.links[0].destination").value("RUNS"));
        mvc.perform(send(UUID.randomUUID(), new Message(UUID.randomUUID(), 0, "What is the test status for Unknown?", Task.ASK, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.reply.links[0].destination").value("RUNS"));
        verifyNoInteractions(provider);
    }

    private void setRunName(UUID run, String name) {
        db.update("""
                UPDATE experiment_input_snapshot SET inputs_json = jsonb_build_object('target', jsonb_build_object('name', ?::text))::text
                WHERE id = (SELECT snapshot_id FROM experiment_run WHERE id = ?)
                """, name, run);
    }

    @Test void groundsReportFollowUpsInAnExplicitReportFromTheSameChat() throws Exception {
        UUID chat = UUID.randomUUID(), report = UUID.randomUUID(), run = completedRun();
        doReturn(viewWithPartial(run, 40)).when(runResults).read(run);
        when(provider.analyzeRun(any(), any())).thenReturn("""
                {"findings":[{"hypothesis":"A transient delay is possible.","suggestedCheck":"Inspect a comparable window.",
                "evidenceIds":["PARTIAL_HTTP"]}]}
                """);
        mvc.perform(analyze(chat, report, run)).andExpect(status().isOk());
        String question = "Can you explain that diagnosis?";
        when(provider.advise(any())).thenReturn("The partial window supports a hypothesis about a short delay.");
        var followUp = new Message(UUID.randomUUID(), 1, question, Task.REPORT, report);
        mvc.perform(send(chat, followUp))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.reply.suggestion", containsString("partial window")));
        mvc.perform(send(chat, followUp)).andExpect(status().isOk()).andExpect(jsonPath("$.turn.task").value("REPORT"));
        assertThat(db.queryForObject("SELECT task FROM assistant_turn WHERE conversation_id = ? AND request_id = ?",
                String.class, chat, followUp.requestId())).isEqualTo("ASK");
        mvc.perform(send(UUID.randomUUID(), new Message(UUID.randomUUID(), 0, question, Task.REPORT, report)))
                .andExpect(status().isNotFound());
        addOtherOperator();
        mvc.perform(send(chat, new Message(UUID.randomUUID(), 2, question, Task.REPORT, report))
                .with(user("other-operator").roles("OPERATOR"))).andExpect(status().isNotFound());
        verify(provider).advise(argThat(request -> {
            var context = request.reportContext();
            return context.at("/observations/0/windowSeconds").asDouble() == 5.0
                    && context.at("/findings/0/evidenceIds/0").asText().equals("PARTIAL_HTTP")
                    && !context.toString().contains(run.toString()) && !context.toString().contains("2026-11");
        }));
    }

    @Test void storesGroundedRunAnalysisAndReplaysItWithoutAnotherProviderCall() throws Exception {
        UUID id = UUID.randomUUID(), request = UUID.randomUUID(), run = completedRun();
        doReturn(viewWithPartial(run, 40)).when(runResults).read(run);
        when(provider.analyzeRun(any(), any())).thenReturn("""
                {"findings":[{"hypothesis":"The partial window may reflect a transient delay.",
                "suggestedCheck":"Check whether the latency increase repeats in a comparable measurement window.",
                "evidenceIds":["PARTIAL_HTTP"],"suggestedChange":"CAPTURE_POST_WARMUP_WINDOW"}]}
                """);
        mvc.perform(analyze(id, request, run)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.analysis.status").value("READY"))
                .andExpect(jsonPath("$.turn.reply.analysis.verdict").value("UNEVALUATED"))
                .andExpect(jsonPath("$.turn.reply.analysis.observations[0].httpRequests").value(40))
                .andExpect(jsonPath("$.turn.reply.analysis.findings[0].evidenceIds[0]").value("PARTIAL_HTTP"))
                .andExpect(jsonPath("$.turn.reply.links[0].recordId").value(run.toString()));
        mvc.perform(analyze(id, request, run)).andExpect(status().isOk());
        mvc.perform(get(CHATS + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].reply.analysis.observations[0].from").value("2026-11-15T00:00:00Z"))
                .andExpect(jsonPath("$.turns[0].reply.analysis.findings[0].suggestedCheck", containsString("comparable measurement window")))
                .andExpect(jsonPath("$.turns[0].reply.analysis.findings[0].suggestedChange").value("CAPTURE_POST_WARMUP_WINDOW"));
        verify(provider).analyzeRun(argThat(evidence -> evidence.at("/observations/0/httpRequests").asInt() == 40
                        && !evidence.toString().contains(run.toString()) && !evidence.toString().contains("internal-")),
                argThat(schema -> schema.toString().contains("PARTIAL_HTTP") && !schema.toString().contains("WHOLE_RUN")
                        && !schema.toString().contains("ISOLATE_FAILING_REQUEST")));
        assertThat(db.queryForObject("SELECT status FROM experiment_run WHERE id = ?", String.class, run)).isEqualTo("COMPLETED");
        addOtherOperator();
        mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), run).with(user("other-operator").roles("OPERATOR")))
                .andExpect(status().isNotFound());
        verifyNoMoreInteractions(provider);
    }

    @Test void refusesEmptyEvidenceAndKeepsObservationsWhenModelCitationsAreInvalid() throws Exception {
        UUID run = completedRun();
        doReturn(viewWithPartial(run, 0), viewWithPartial(run, 40), viewWithPartial(run, 40)).when(runResults).read(run);
        mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), run)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.analysis.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.turn.reply.analysis.observations.length()").value(0));
        verifyNoInteractions(provider);
        when(provider.analyzeRun(any(), any())).thenReturn("""
                {"findings":[{"hypothesis":"Unverified observation.","suggestedCheck":"Inspect the sample.","evidenceIds":["WHOLE_RUN"]}]}
                """).thenThrow(new IllegalStateException("provider offline"));
        for (var issue : List.of("INVALID_RESPONSE", "UNAVAILABLE")) {
            mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), run)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.turn.reply.analysis.status").value("PROVIDER_UNAVAILABLE"))
                    .andExpect(jsonPath("$.turn.reply.analysis.providerIssue").value(issue))
                    .andExpect(jsonPath("$.turn.reply.analysis.verdict").value("UNEVALUATED"))
                    .andExpect(jsonPath("$.turn.reply.analysis.observations.length()").value(1))
                    .andExpect(jsonPath("$.turn.reply.analysis.findings.length()").value(0));
        }
    }

    @Test void keepsWholeRunAndPartialWindowsAsSeparateEvidence() throws Exception {
        UUID run = completedRun();
        doReturn(viewWithWhole(run, 210.0)).when(runResults).read(run);
        when(provider.analyzeRun(any(), any())).thenReturn("{\"findings\":[]}");
        mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), run)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.analysis.status").value("READY"))
                .andExpect(jsonPath("$.turn.reply.analysis.observations[0].id").value("WHOLE_RUN"))
                .andExpect(jsonPath("$.turn.reply.analysis.observations[0].p95LatencyMs").value(210.0))
                .andExpect(jsonPath("$.turn.reply.analysis.observations[1].id").value("PARTIAL_HTTP"))
                .andExpect(jsonPath("$.turn.reply.analysis.observations[1].p95LatencyMs").value(140.0));
        verify(provider).analyzeRun(argThat(evidence -> evidence.path("observations").size() == 2
                        && !evidence.toString().contains("internal-") && !evidence.toString().contains("2026-11")), any());
    }

    @Test void checksSuggestedChangesAgainstCitedObservationsAndRejectsUnsupportedCodes() throws Exception {
        UUID run = completedRun();
        doReturn(viewWithWhole(run, 210.0)).when(runResults).read(run);
        when(provider.analyzeRun(any(), any())).thenReturn("""
                {"findings":[
                {"hypothesis":"The sample may precede the failures.","suggestedCheck":"Inspect its timing.",
                "evidenceIds":["PARTIAL_HTTP"],"suggestedChange":"ISOLATE_FAILING_REQUEST"},
                {"hypothesis":"Some requests may fail outside the sample.","suggestedCheck":"Group the recorded failures by response status.",
                "evidenceIds":["WHOLE_RUN"],"suggestedChange":"ISOLATE_FAILING_REQUEST"}]}
                """, """
                {"findings":[{"hypothesis":"The run has historical observations.","suggestedCheck":"Review the results.",
                "evidenceIds":["WHOLE_RUN"],"suggestedChange":"RAISE_MAXIMUM_RATE"}]}
                """);
        mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), run)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.analysis.status").value("READY"))
                .andExpect(jsonPath("$.turn.reply.analysis.findings[0].suggestedChange").doesNotExist())
                .andExpect(jsonPath("$.turn.reply.analysis.findings[1].suggestedChange").value("ISOLATE_FAILING_REQUEST"));
        mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), run)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.analysis.providerIssue").value("INVALID_RESPONSE"))
                .andExpect(jsonPath("$.turn.reply.analysis.observations.length()").value(2))
                .andExpect(jsonPath("$.turn.reply.analysis.verdict").value("UNEVALUATED"));
    }

    @Test void comparesCompatibleRunsWithPairedCitationsAndReplaysOnlyTheSamePair() throws Exception {
        UUID baseline = completedRun(), comparison = completedRun(targetId(baseline)), chat = UUID.randomUUID(), request = UUID.randomUUID();
        stubComparisonInputs(baseline, 5);
        stubComparisonInputs(comparison, 5);
        doReturn(viewWithWhole(baseline, 210.0)).when(runResults).read(baseline);
        doReturn(viewWithWhole(comparison, 180.0)).when(runResults).read(comparison);
        when(provider.analyzeRun(any(), any())).thenReturn("""
                {"findings":[{"hypothesis":"The whole-run difference may reflect variability.",
                "suggestedCheck":"Compare equivalent measurement windows with achieved load recorded.",
                "evidenceIds":["WHOLE_RUN","COMPARISON_WHOLE_RUN"],"suggestedChange":"REPEAT_MATCHED_RUN"}]}
                """);
        mvc.perform(analyze(chat, request, baseline, comparison)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.analysis.comparison.runId").value(comparison.toString()))
                .andExpect(jsonPath("$.turn.reply.analysis.comparison.verdict").value("UNEVALUATED"))
                .andExpect(jsonPath("$.turn.reply.analysis.observations.length()").value(2))
                .andExpect(jsonPath("$.turn.reply.analysis.observations[1].p95LatencyMs").value(180.0));
        mvc.perform(analyze(chat, request, baseline, comparison)).andExpect(status().isOk());
        mvc.perform(analyze(chat, request, baseline, UUID.randomUUID())).andExpect(status().isConflict());
        mvc.perform(get(CHATS + "/" + chat)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].reply.analysis.findings[0].evidenceIds[1]").value("COMPARISON_WHOLE_RUN"))
                .andExpect(jsonPath("$.turns[0].reply.analysis.findings[0].suggestedChange").value("REPEAT_MATCHED_RUN"));
        verify(provider).analyzeRun(argThat(evidence -> evidence.path("requestedIterationsPerSecond").asInt() == 5
                && evidence.path("observations").size() == 2 && !evidence.toString().contains(comparison.toString())), any());
    }

    @Test void skipsComparisonProviderForIncompatibleRunsOrMissingPairedEvidence() throws Exception {
        UUID baseline = completedRun(), comparison = completedRun(targetId(baseline));
        stubComparisonInputs(baseline, 5);
        stubComparisonInputs(comparison, 6);
        doReturn(viewWithWhole(baseline, 210.0)).when(runResults).read(baseline);
        doReturn(viewWithPartial(comparison, 40)).when(runResults).read(comparison);
        mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), baseline, comparison)).andExpect(status().isUnprocessableEntity());
        stubComparisonInputs(comparison, 5);
        mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), baseline, comparison)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.reply.analysis.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.turn.reply.analysis.observations.length()").value(1));
        addOtherOperator();
        db.update("UPDATE experiment_run SET created_by = 'other-operator' WHERE id = ?", comparison);
        mvc.perform(analyze(UUID.randomUUID(), UUID.randomUUID(), baseline, comparison)).andExpect(status().isNotFound());
        verifyNoInteractions(provider);
    }

    @Test void listsOnlyOwnedCompatibleComparisonRunsWithUsableWholeEvidence() throws Exception {
        UUID baseline = completedRun(), target = targetId(baseline);
        UUID eligible = completedRun(target), differentRate = completedRun(target), partialOnly = completedRun(target);
        stubComparisonInputs(baseline, 5);
        stubComparisonInputs(eligible, 5);
        stubComparisonInputs(differentRate, 6);
        stubComparisonInputs(partialOnly, 5);
        doReturn(viewWithWhole(baseline, 210.0)).when(runResults).read(baseline);
        doReturn(viewWithWhole(eligible, 180.0).result()).when(runResults).saved(eligible);
        addWorkerResultRow(eligible);
        addWorkerResultRow(differentRate);
        String path = "/api/assistant/runs/" + baseline + "/comparison-options";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(eligible.toString()))
                .andExpect(jsonPath("$.hasMore").value(false));
        addOtherOperator();
        db.update("UPDATE experiment_run SET created_by = 'other-operator' WHERE id = ?", eligible);
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(path).with(user("other-operator").roles("OPERATOR"))).andExpect(status().isNotFound());
        verifyNoInteractions(provider);
    }

    private void addWorkerResultRow(UUID run) {
        UUID attempt = db.queryForObject("SELECT id FROM experiment_attempt WHERE run_id = ?", UUID.class, run);
        UUID operation = UUID.randomUUID(), plan = UUID.randomUUID();
        db.update("INSERT INTO experiment_operation(id,attempt_id,kind,status) VALUES (?,?,'SUBMIT','CONFIRMED')", operation, attempt);
        db.update("""
                INSERT INTO experiment_execution_plan(id,run_id,attempt_id,submit_operation_id,run_revision,sha256,plan_json,prepared_by)
                VALUES (?,?,?,?,1,?,'{}','admin')
                """, plan, run, attempt, operation, "a".repeat(64));
        db.update("""
                INSERT INTO experiment_worker_result(attempt_id,run_id,plan_id,job_uid,pod_uid,sha256,result_json)
                VALUES (?,?,?,'fixture-job','fixture-pod',?,'{}')
                """, attempt, run, plan, "a".repeat(64));
    }

    private UUID targetId(UUID run) {
        return db.queryForObject("SELECT target_id FROM experiment_run WHERE id = ?", UUID.class, run);
    }

    private void stubComparisonInputs(UUID run, int rate) {
        UUID snapshot = db.queryForObject("SELECT snapshot_id FROM experiment_run WHERE id = ?", UUID.class, run);
        var at = OffsetDateTime.parse("2026-11-15T00:00:00Z");
        var target = new ExperimentSnapshots.TargetInput(targetId(run), "Fixture", "https://fixture.invalid", "STAGING", "fixture", "api", "api", 1);
        var script = new ScenarioApi.Version(UUID.randomUUID(), "Script", 1, "fixture script", "a".repeat(64), "admin", at, true);
        var approved = new ScenarioStore.ApprovedVersion(script, UUID.randomUUID(), UUID.randomUUID(), "static-review-v1", "8.19.0", "admin", at);
        var settings = new Settings(rate, rate, 30, 5, 150, 0.01, 1, 2, 120);
        var bounds = new ExecutionBounds(null, 2, 10, 1, 250, 128, 500, 10, 5, 1);
        var profile = new LoadProfile(LoadProfile.Mode.SINGLE_RUN, LoadProfile.Shape.STEADY, rate, List.of(new LoadProfile.Stage(30, rate)), 25);
        var policy = new SloPolicy(SloPolicy.Percentile.P95, 150, 0.01, SloPolicy.SuccessDefinition.HTTP_200_399_WITHOUT_TRANSPORT_ERROR,
                null, null, null, List.of());
        var inputs = new ExperimentSnapshots.Inputs("experiment-input-v5", UUID.randomUUID(), 1, "SINGLE_RUN", target, approved,
                settings, "iterations/s", "constant-arrival-rate", 25, "<=", bounds, 45L, 1, profile, rate, policy);
        doReturn(new ExperimentSnapshots.Snapshot(snapshot, inputs, "b".repeat(64), "admin", at, true, false)).when(snapshots).get(snapshot);
    }

    private MockHttpServletRequestBuilder analyze(UUID id, UUID request, UUID run) throws Exception {
        return analyze(id, request, run, null);
    }

    private MockHttpServletRequestBuilder analyze(UUID id, UUID request, UUID run, UUID comparison) throws Exception {
        return post(CHATS + "/" + id + "/analysis").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(new AnalysisRequest(request, 0, run, comparison)));
    }

    private static RunResults.View viewWithWhole(UUID run, double p95) {
        UUID attempt = UUID.randomUUID(), shard = UUID.randomUUID();
        var partial = viewWithPartial(run, 40);
        var timing = new K6TerminalSummary.DurationMs(5.0, 300.0, 100.0, 85.0, 160.0, p95, 290.0);
        var http = new K6TerminalSummary.Summary(K6TerminalSummary.SCHEMA, run, attempt, shard, "internal-snapshot", "internal-script",
                30000.0, 160L, 0.10, 160L, 1L, timing);
        var whole = new RunResults.Evidence(run, attempt, shard, UUID.randomUUID(), "internal-plan", "internal-job", "internal-pod",
                partial.partialHttp().windowFrom(), partial.partialHttp().windowFrom().plusSeconds(30), 0,
                "k6 handleSummary / Kubernetes termination message", "WHOLE_RUN_INCLUDING_WARMUP_NOT_SLO", "AVAILABLE", http);
        return new RunResults.View(run, "COMPLETED", "UNEVALUATED", "AVAILABLE",
                new RunResults.Saved(whole, "internal-result", OffsetDateTime.parse("2026-11-15T00:00:30Z")), partial.partialHttp());
    }

    private static RunResults.View viewWithPartial(UUID run, int requests) {
        Instant from = Instant.parse("2026-11-15T00:00:00Z");
        var identity = new K6LiveHttpMetrics.Identity(run, UUID.randomUUID(), UUID.randomUUID(), "internal-job");
        var window = new RunPartialResults.Window(identity, UUID.randomUUID(), "internal-plan", "internal-pod", from,
                from.plusSeconds(5), from.plusSeconds(5), requests, 0, 8.0, 140.0, 0.0,
                "k6 JSON / Kubernetes Pod log", "live-http-json-v1/type7", "PARTIAL_HTTP_WINDOW_NOT_SLO");
        return new RunResults.View(run, "COMPLETED", "UNEVALUATED", "NOT_COLLECTED", null, window);
    }

    private MockHttpServletRequestBuilder send(UUID id, Message message) throws Exception {
        return post(CHATS + "/" + id + "/messages").with(csrf()).contentType("application/json").content(json.writeValueAsString(message));
    }

    private void addOtherOperator() {
        db.update("INSERT INTO users(username,password,enabled) SELECT 'other-operator',password,true FROM users WHERE username='admin'");
        db.update("INSERT INTO authorities(username,authority) VALUES ('other-operator','ROLE_OPERATOR')");
    }

    private UUID completedRun() { return completedRun(null); }

    private UUID completedRun(UUID target) {
        UUID draft = UUID.randomUUID(), snapshot = UUID.randomUUID(), run = UUID.randomUUID();
        if (target == null) {
            target = UUID.randomUUID();
            db.update("INSERT INTO registered_target(id,name,base_url,environment,namespace,deployment,container) VALUES (?,?,'https://fixture.invalid','STAGING','fixture','api','api')", target, "Assistant fixture " + target);
        }
        db.update("INSERT INTO experiment_draft(id,target_id,settings,created_by) VALUES (?,?,'{}','admin')", draft, target);
        db.update("""
                INSERT INTO experiment_input_snapshot(id,draft_id,draft_revision,sha256,inputs_json,created_by)
                VALUES (?,?,1,?,'{}','admin')
                """, snapshot, draft, "a".repeat(64));
        db.update("""
                INSERT INTO experiment_run(id,snapshot_id,target_id,request_key,status,deadline_at,created_by)
                VALUES (?,?,?,?,'COMPLETED',CURRENT_TIMESTAMP + INTERVAL '1 minute','admin')
                """, run, snapshot, target, UUID.randomUUID());
        db.update("""
                INSERT INTO experiment_attempt(id,run_id,attempt_number,shard_id,worker_name,maximum_seconds,worker_state)
                VALUES (?,?,1,?,?,60,'SUCCEEDED')
                """, UUID.randomUUID(), run, UUID.randomUUID(), "assistant-" + run);
        return run;
    }
}
