package dev.loadpilot.access;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeds an empty account store once. Existing accounts always remain authoritative. */
@Component
public class AccountBootstrap implements ApplicationRunner {
    private final JdbcTemplate database;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;
    private final String username;
    private final String password;

    public AccountBootstrap(JdbcTemplate database, PasswordEncoder encoder, PlatformTransactionManager transactions,
                            @Value("${platform.username:admin}") String username,
                            @Value("${platform.password:}") String password) {
        this.database = database;
        this.encoder = encoder;
        this.transaction = new TransactionTemplate(transactions);
        this.username = username;
        this.password = password;
    }

    @Override public void run(ApplicationArguments args) {
        transaction.executeWithoutResult(status -> {
            // Serializes first startup across controllers without racing two bootstrap identities.
            database.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
            if (database.queryForObject("SELECT count(*) FROM users", Long.class) != 0) return;
            int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
            if (username.isBlank() || !username.equals(username.trim()) || username.length() > 128)
                throw new IllegalArgumentException("Invalid bootstrap PLATFORM_USER");
            if (passwordBytes < 8 || passwordBytes > 72)
                throw new IllegalArgumentException("Initial PLATFORM_PASSWORD must contain 8 to 72 UTF-8 bytes");
            database.update("INSERT INTO users(username, password, enabled) VALUES (?, ?, true)", username, encoder.encode(password));
            database.update("INSERT INTO authorities(username, authority) VALUES (?, 'ROLE_OPERATOR')", username);
            database.update("INSERT INTO access_audit(actor, action, object_type, outcome) VALUES (?, 'ACCOUNT_BOOTSTRAP', 'ACCOUNT', 'SUCCESS')", username);
        });
    }
}
