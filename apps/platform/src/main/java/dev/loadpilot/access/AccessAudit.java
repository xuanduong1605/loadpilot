package dev.loadpilot.access;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccessAudit {
    private final JdbcTemplate database;
    private final AccessResponses responses;
    public AccessAudit(JdbcTemplate database, AccessResponses responses) {
        this.database = database;
        this.responses = responses;
    }

    public boolean record(HttpServletResponse response, String actor, String action, String outcome) throws IOException {
        try {
            database.update("INSERT INTO access_audit(actor, action, object_type, outcome) VALUES (?, ?, 'SESSION', ?)",
                    actor, action, outcome);
            return true;
        } catch (DataAccessException unavailable) {
            responses.error(response, 503, "ACCESS_UNAVAILABLE", "Sign-in service is unavailable. Try again later.");
            return false;
        }
    }
}
