package dev.loadpilot.access;

import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.JdbcUserDetailsManager;

/** Keep driver/SQL details out of authentication failures and framework auth logs. */
public class DatabaseAccounts extends JdbcUserDetailsManager {
    public DatabaseAccounts(DataSource source) { super(source); }

    @Override public UserDetails loadUserByUsername(String username) {
        try {
            return super.loadUserByUsername(username);
        } catch (DataAccessException unavailable) {
            throw new AuthenticationServiceException("Account storage is unavailable.");
        }
    }
}
