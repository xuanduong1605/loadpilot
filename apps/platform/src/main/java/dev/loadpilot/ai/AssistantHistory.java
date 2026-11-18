package dev.loadpilot.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.ai.AssistantApi.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
class AssistantHistory {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_TURNS = 50;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    AssistantHistory(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    record State(Sent replay, List<Turn> recent) {}
    private record Owned(String operator, Summary summary) {}

    History list(String operator, int offset) {
        var rows = db.query("""
                SELECT * FROM assistant_conversation WHERE operator_name = ?
                ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?
                """, (row, index) -> summary(row), operator, PAGE_SIZE + 1, offset);
        return new History(rows.subList(0, Math.min(PAGE_SIZE, rows.size())), rows.size() > PAGE_SIZE);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Conversation conversation(UUID id, String operator) {
        var summary = find(id, operator, false);
        if (summary == null) throw notFound();
        var turns = db.query("SELECT * FROM assistant_turn WHERE conversation_id = ? ORDER BY turn_number",
                (row, index) -> turn(row), id);
        return new Conversation(summary, turns);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public State inspect(UUID id, String operator, Message request) {
        var summary = find(id, operator, false);
        var replay = check(summary, request);
        if (replay != null || summary == null) return new State(replay, List.of());
        var recent = db.query("""
                SELECT * FROM assistant_turn WHERE conversation_id = ? AND turn_number > ? ORDER BY turn_number
                """, (row, index) -> turn(row), id, Math.max(0, summary.turnCount() - 4));
        return new State(null, recent);
    }

    @Transactional
    public Sent append(UUID id, String operator, Message request, Reply reply) {
        if (request.expectedTurns() == 0) {
            String line = request.text().strip().replaceAll("\\s+", " ");
            String title = line.codePointCount(0, line.length()) <= 80 ? line : line.substring(0, line.offsetByCodePoints(0, 77)) + "…";
            db.update("INSERT INTO assistant_conversation(id, operator_name, title) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    id, operator, title);
        }
        var summary = find(id, operator, true);
        if (summary == null) throw notFound();
        var replay = check(summary, request);
        if (replay != null) return replay;
        int number = summary.turnCount() + 1;
        var turn = db.queryForObject("""
                INSERT INTO assistant_turn(conversation_id, turn_number, request_id, prompt, task, record_id, reply_json)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, (row, index) -> turn(row), id, number, request.requestId(), request.text(), request.task().name(),
                request.recordId(), encode(reply));
        db.update("UPDATE assistant_conversation SET turn_count = ?, updated_at = ? WHERE id = ?", number, turn.createdAt(), id);
        return new Sent(new Summary(id, summary.title(), number, turn.createdAt()), turn);
    }

    private Sent check(Summary summary, Message request) {
        if (summary != null) {
            var existing = db.query("SELECT * FROM assistant_turn WHERE conversation_id = ? AND request_id = ?",
                    (row, index) -> turn(row), summary.id(), request.requestId());
            if (!existing.isEmpty()) {
                var turn = existing.getFirst();
                if (turn.number() != request.expectedTurns() + 1 || !turn.text().equals(request.text())
                        || turn.task() != request.task() || !java.util.Objects.equals(turn.recordId(), request.recordId()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This send was already used for a different message.");
                return new Sent(summary, turn);
            }
        }
        int count = summary == null ? 0 : summary.turnCount();
        if (count != request.expectedTurns())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This conversation changed. Reload it before sending another message.");
        if (count == MAX_TURNS)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Start a new chat to continue. This conversation has 50 turns.");
        return null;
    }

    private Summary find(UUID id, String operator, boolean lock) {
        var rows = db.query("SELECT * FROM assistant_conversation WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Owned(row.getString("operator_name"), summary(row)), id);
        if (rows.isEmpty()) return null;
        var owned = rows.getFirst();
        if (!operator.equals(owned.operator())) throw notFound();
        return owned.summary();
    }

    private static Summary summary(ResultSet row) throws SQLException {
        return new Summary(row.getObject("id", UUID.class), row.getString("title"), row.getInt("turn_count"),
                row.getObject("updated_at", OffsetDateTime.class));
    }

    private Turn turn(ResultSet row) throws SQLException {
        try {
            return new Turn(row.getInt("turn_number"), row.getObject("request_id", UUID.class), row.getString("prompt"),
                    Task.valueOf(row.getString("task")), row.getObject("record_id", UUID.class),
                    json.readValue(row.getString("reply_json"), Reply.class), row.getObject("created_at", OffsetDateTime.class));
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Saved assistant reply cannot be read.");
        }
    }

    private String encode(Reply reply) {
        try {
            return json.writeValueAsString(reply);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Assistant reply cannot be saved.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
    }
}
