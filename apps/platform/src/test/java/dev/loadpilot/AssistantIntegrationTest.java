package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.ai.AssistantApi.Message;
import dev.loadpilot.ai.AssistantApi.Task;
import dev.loadpilot.ai.AssistantProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoBean AssistantProvider provider;

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

    @Test void readsAnOwnedRunAndKeepsMissingEvidenceAndRunStartSeparate() throws Exception {
        UUID id = UUID.randomUUID();
        UUID run = completedRun();
        mvc.perform(send(id, new Message(UUID.randomUUID(), 0, "Check my test status.", Task.STATUS, null)))
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

    private MockHttpServletRequestBuilder send(UUID id, Message message) throws Exception {
        return post(CHATS + "/" + id + "/messages").with(csrf()).contentType("application/json").content(json.writeValueAsString(message));
    }

    private void addOtherOperator() {
        db.update("INSERT INTO users(username,password,enabled) SELECT 'other-operator',password,true FROM users WHERE username='admin'");
        db.update("INSERT INTO authorities(username,authority) VALUES ('other-operator','ROLE_OPERATOR')");
    }

    private UUID completedRun() {
        UUID target = UUID.randomUUID(), draft = UUID.randomUUID(), snapshot = UUID.randomUUID(), run = UUID.randomUUID();
        db.update("INSERT INTO registered_target(id,name,base_url,environment,namespace,deployment,container) VALUES (?,?,'https://fixture.invalid','STAGING','fixture','api','api')", target, "Assistant fixture " + target);
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
