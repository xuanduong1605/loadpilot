package dev.loadpilot;

import dev.loadpilot.access.DatabaseAccounts;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.AuthenticationServiceException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseAccountsTest {
    @Test void databaseFailureCannotLeakDriverDetailsThroughFrameworkAuthenticationLogs() {
        var database = mock(JdbcTemplate.class);
        var accounts = new DatabaseAccounts(mock(DataSource.class));
        accounts.setJdbcTemplate(database);
        when(database.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<org.springframework.security.core.userdetails.UserDetails>>any(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("private-driver-and-database-details"));
        assertThatThrownBy(() -> accounts.loadUserByUsername("admin"))
                .isInstanceOf(AuthenticationServiceException.class)
                .hasMessage("Account storage is unavailable.").hasNoCause();
    }
}
