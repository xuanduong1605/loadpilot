package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.targets.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import dev.loadpilot.audit.TargetAudit;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.username=admin","platform.password=integration-test-password-123",
        "platform.connections.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "platform.connections.allowed-origins=https://127.0.0.1:18443,https://127.0.0.1:18444", "platform.connections.allowed-addresses=127.0.0.1",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}","spring.datasource.password=${TEST_DATABASE_PASSWORD}"})
@AutoConfigureMockMvc @WithMockUser(username="admin",roles="OPERATOR") @Transactional
class ConnectionIntegrationTest {
    @Autowired MockMvc mvc; @Autowired TargetStore targets; @Autowired JdbcTemplate database; @Autowired ObjectMapper json;
    @MockitoSpyBean TargetAudit audit;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("connectionRequestLimit")
    org.springframework.boot.web.servlet.FilterRegistrationBean<org.springframework.web.filter.OncePerRequestFilter> bodyLimit;
    Target create() { return targets.create(new TargetInput("Connection fixture " + java.util.UUID.randomUUID(),"https://fixture.example.invalid","STAGING","fixture","fixture-api","api",null,null,null),"admin"); }
    ObjectNode input(long revision,long targetRevision) { return json.createObjectNode().put("revision",revision).put("targetRevision",targetRevision)
            .put("kubernetesUrl","https://127.0.0.1:18443").put("kubernetesCa","").put("kubernetesToken","fixture-readonly-token")
            .put("prometheusUrl","").put("prometheusCa","").put("prometheusToken","").put("readOnlyAcknowledged",true); }
    @Test void savesEncryptedWriteOnlyTokensBindsReferencesAndRejectsLostUpdates() throws Exception {
        var target = create(); String url="/api/integrations/targets/"+target.id()+"/connection";
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0));
        mvc.perform(put(url).with(csrf()).contentType("application/json").content(input(0,1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.hasKubernetesToken").value(true))
                .andExpect(jsonPath("$.kubernetesToken").doesNotExist());
        String sealed = database.queryForObject("SELECT kubernetes_token FROM system_connection WHERE target_id=?",String.class,target.id());
        assertThat(sealed).doesNotContain("fixture-readonly-token");
        assertThat(targets.get(target.id()).credentialRef()).isEqualTo(target.id().toString());
        mvc.perform(get(url)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fixture-readonly-token"))));
        mvc.perform(put(url).with(csrf()).contentType("application/json").content(input(1,2).put("kubernetesToken","").toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        assertThat(database.queryForObject("SELECT kubernetes_token FROM system_connection WHERE target_id=?",String.class,target.id())).isEqualTo(sealed);
        mvc.perform(put(url).with(csrf()).contentType("application/json").content(input(2,3).put("kubernetesToken","").put("kubernetesUrl","https://127.0.0.1:18444").toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPLACEMENT_TOKEN_REQUIRED"));
        mvc.perform(put(url).with(csrf()).contentType("application/json").content(input(1,2).toString())).andExpect(status().isConflict());
        assertThat(database.queryForObject("SELECT count(*) FROM access_audit WHERE action='CONNECTION_SAVED' AND object_id=?",Integer.class,target.id())).isEqualTo(2);
    }
    @Test void deniesUnapprovedOriginsInvalidCertificatesCsrfAndPublicHttpWithoutLeakingInput() throws Exception {
        var target=create(); String url="/api/integrations/targets/"+target.id()+"/connection";
        mvc.perform(put(url).with(csrf()).contentType("application/json").content(input(0,1).put("kubernetesUrl","https://unrelated.example.invalid").toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DESTINATION_DENIED"));
        mvc.perform(put(url).with(csrf()).contentType("application/json").content(input(0,1).put("kubernetesCa","private-invalid-certificate").toString()))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-invalid-certificate"))));
        String ca = new okhttp3.tls.HeldCertificate.Builder().commonName("certificate-only-fixture").build().certificatePem();
        mvc.perform(put(url).with(csrf()).contentType("application/json").content(input(0,1).put("kubernetesCa",ca+"\n-----BEGIN PRIVATE KEY-----\nZmFrZQ==\n-----END PRIVATE KEY-----").toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(put(url).contentType("application/json").content(input(0,1).toString())).andExpect(status().isForbidden());
        mvc.perform(put(url).with(csrf()).contentType("application/json").content(input(0,1).put("readOnlyAcknowledged",false).toString())).andExpect(status().isBadRequest());
        mvc.perform(put(url).with(csrf()).with(request->{request.setServerName("public.example.invalid");return request;}).contentType("application/json").content(input(0,1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SECURE_TRANSPORT_REQUIRED"));
        mvc.perform(get(url).with(anonymous())).andExpect(status().isUnauthorized());
        assertThat(database.queryForObject("SELECT count(*) FROM system_connection WHERE target_id=?",Integer.class,target.id())).isZero();
    }
    @Test void oversizedRequestsAreBoundedBeforeCredentialDeserialization() throws Exception {
        var target=create(); String url="/api/integrations/targets/"+target.id()+"/connection";
        mvc.perform(put(url).with(csrf()).contentType("application/json").content("x".repeat(32769)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        var chunked = new org.springframework.mock.web.MockHttpServletRequest("PUT",url) {
            @Override public long getContentLengthLong() { return -1; }
        };
        chunked.setContent("x".repeat(32769).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bodyLimit.getFilter().doFilter(chunked,new org.springframework.mock.web.MockHttpServletResponse(),(request,response)->
                org.assertj.core.api.Assertions.assertThatThrownBy(()->request.getInputStream().readAllBytes()).isInstanceOf(java.io.IOException.class));
    }
    @Test void unreadableCredentialReportsUnavailableWithoutPretendingTheProfileChanged() throws Exception {
        var target=create();String base="/api/integrations/targets/"+target.id();
        mvc.perform(put(base+"/connection").with(csrf()).contentType("application/json").content(input(0,1).toString())).andExpect(status().isOk());
        database.update("UPDATE system_connection SET kubernetes_token='unreadable-ciphertext' WHERE target_id=?",target.id());
        mvc.perform(get(base+"/status")).andExpect(status().isOk()).andExpect(jsonPath("$.connectionRevision").value(1))
                .andExpect(jsonPath("$.kubernetes.state").value("CONNECTION_UNAVAILABLE")).andExpect(jsonPath("$.pods.length()").value(0))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unreadable-ciphertext"))));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void failedAuditRollsBackEncryptedProfileAndTargetReferenceTogether() throws Exception {
        var target=create();
        try {
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("private-audit-detail"))
                    .when(audit).record(anyString(),eq("CONNECTION_SAVED"),eq(target.id()));
            mvc.perform(put("/api/integrations/targets/"+target.id()+"/connection").with(csrf()).contentType("application/json").content(input(0,1).toString()))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CONNECTION_UNAVAILABLE"));
            assertThat(database.queryForObject("SELECT count(*) FROM system_connection WHERE target_id=?",Integer.class,target.id())).isZero();
            assertThat(targets.get(target.id()).credentialRef()).isNull();
            assertThat(targets.get(target.id()).revision()).isEqualTo(1);
        } finally {
            database.update("DELETE FROM access_audit WHERE object_id=?",target.id());
            database.update("DELETE FROM registered_target WHERE id=?",target.id());
        }
    }
}
