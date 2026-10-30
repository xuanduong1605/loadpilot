package dev.loadpilot;

import dev.loadpilot.access.AccountBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// All account mutations below roll back in a dedicated native test database.
@SpringBootTest(properties = {
        "platform.username=admin", "platform.password=integration-test-password-123",
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:5432/loadpilot_test}",
        "spring.datasource.username=${TEST_DATABASE_USER:loadpilot_test}", "spring.datasource.password=${TEST_DATABASE_PASSWORD}"})
@AutoConfigureMockMvc
@Transactional
class AccountIntegrationTest {
    private static final String PASSWORD = "Demo!2345";
    @Autowired JdbcTemplate database;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcUserDetailsManager users;
    @Autowired MockMvc mvc;

    @BeforeEach void isolatedAccounts() {
        database.update("DELETE FROM authorities");
        database.update("DELETE FROM users");
    }

    private void seed(String username, String password) {
        new AccountBootstrap(database, encoder, transactions, username, password).run(null);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/access/login").with(csrf())
                .param("username", "account-seed").param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn().getRequest().getSession(false);
    }

    @Test void storesOnlyBcryptAndAuthenticatesFromFreshJdbcReader() throws Exception {
        seed("account-seed", PASSWORD);
        String hash = database.queryForObject("SELECT password FROM users WHERE username='account-seed'", String.class);
        assertThat(hash).startsWith("$2a$").hasSize(60).doesNotContain(PASSWORD);
        var reloaded = new JdbcUserDetailsManager(database.getDataSource()).loadUserByUsername("account-seed");
        assertThat(encoder.matches(PASSWORD, reloaded.getPassword())).isTrue();
        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(reloaded.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATOR");
        mvc.perform(get("/api/access/session").session(login())).andExpect(status().isOk());
        mvc.perform(post("/api/access/login").with(csrf()).param("username", "account-seed").param("password", "wrong-password"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test void changedOrAbsentBootstrapEnvironmentCannotResetOrReenableSavedAccounts() {
        seed("account-seed", PASSWORD);
        String hash = users.loadUserByUsername("account-seed").getPassword();
        database.update("UPDATE users SET enabled=false WHERE username='account-seed'");
        int auditCount = database.queryForObject("SELECT count(*) FROM access_audit WHERE action='ACCOUNT_BOOTSTRAP'", Integer.class);
        seed("account-seed", "different-bootstrap-password");
        seed("new-admin", "");
        var stored = users.loadUserByUsername("account-seed");
        assertThat(stored.getPassword()).isEqualTo(hash);
        assertThat(stored.isEnabled()).isFalse();
        assertThat(database.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT count(*) FROM access_audit WHERE action='ACCOUNT_BOOTSTRAP'", Integer.class)).isEqualTo(auditCount);
    }



    @Test void failedBootstrapAuditRollsBackAccountAndRoleTogether() {
        var nested = new org.springframework.transaction.support.TransactionTemplate(transactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.executeWithoutResult(status -> {
            database.execute("ALTER TABLE access_audit ADD CONSTRAINT reject_account_test_audit CHECK (action <> 'ACCOUNT_BOOTSTRAP' OR actor <> 'rollback-seed')");
            seed("rollback-seed", PASSWORD);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(database.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM authorities", Integer.class)).isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM access_audit WHERE actor='rollback-seed'", Integer.class)).isZero();
    }

    @Test void emptyStoreRejectsInvalidBootstrapWithoutCreatingAnAccount() {
        assertThatThrownBy(() -> seed("account-seed", "short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> seed("account-seed", "é".repeat(37))).isInstanceOf(IllegalArgumentException.class);
        assertThat(database.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }
}
