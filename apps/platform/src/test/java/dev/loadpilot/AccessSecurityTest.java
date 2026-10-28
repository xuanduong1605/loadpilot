package dev.loadpilot;

import dev.loadpilot.access.AccessApi;
import dev.loadpilot.access.AccessAudit;
import dev.loadpilot.access.AccessResponses;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AccessApi.class, FoundationApi.class}, properties = {
        "platform.username=admin", "platform.password=integration-test-password-123"})
@Import({SecurityConfig.class, AccessResponses.class, AccessAudit.class})
class AccessSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate database;

    @Test void anonymousBasicAndMissingCsrfCannotBypassSessionSecurity() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/status").with(httpBasic("admin", "integration-test-password-123")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/access/login").param("username", "admin").param("password", "integration-test-password-123"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(get("/api/status").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test void wrongLoginIsGenericAndAuditsNoSubmittedUsername() throws Exception {
        mvc.perform(post("/api/access/login").with(csrf()).param("username", "untrusted-input").param("password", "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        verify(database).update(anyString(), isNull(), eq("LOGIN"), eq("DENIED"));
    }

    @Test void realCsrfLoginRotatesSessionAndLogoutRevokesAccess() throws Exception {
        var bootstrap = mvc.perform(get("/api/access/csrf")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var token = json.readTree(bootstrap.getResponse().getContentAsString()).get("token").asText();
        var session = (MockHttpSession) bootstrap.getRequest().getSession(false);
        String oldId = session.getId();
        mvc.perform(post("/api/access/login").session(session).header("X-CSRF-TOKEN", token)
                        .param("username", "admin").param("password", "integration-test-password-123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/access/session").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/status").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/access/logout").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/access/logout").session(session).with(csrf())).andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("LOADPILOT_SESSION", 0));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/access/session")).andExpect(status().isUnauthorized());
        verify(database).update(anyString(), eq("admin"), eq("LOGIN"), eq("SUCCESS"));
        verify(database).update(anyString(), eq("admin"), eq("LOGOUT"), eq("SUCCESS"));
    }

    @Test void databaseAuditFailureCannotEstablishAccess() throws Exception {
        when(database.update(anyString(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("private-detail"));
        var session = new MockHttpSession();
        mvc.perform(post("/api/access/login").session(session).with(csrf())
                        .param("username", "admin").param("password", "integration-test-password-123"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ACCESS_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-detail"))));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void signingInAgainExpiresThePreviousOperatorSession() throws Exception {
        var first = mvc.perform(post("/api/access/login").with(csrf())
                        .param("username", "admin").param("password", "integration-test-password-123"))
                .andExpect(status().isOk()).andReturn();
        var previous = (MockHttpSession) first.getRequest().getSession(false);
        mvc.perform(post("/api/access/login").with(csrf())
                        .param("username", "admin").param("password", "integration-test-password-123"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/access/session").session(previous)).andExpect(status().isUnauthorized());
    }
}
