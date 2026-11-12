package dev.loadpilot.experiments;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments/library")
public class ExperimentLibrary {
    public enum Kind { DRAFTS, SNAPSHOTS, RUNS }

    public record Entry(UUID id, String targetName, String scriptName, Integer scriptVersion,
                        long revision, String status, OffsetDateTime listedAt) {}
    public record Page(List<Entry> items, boolean hasMore) {}

    private final JdbcTemplate db;

    public ExperimentLibrary(JdbcTemplate db) { this.db = db; }

    @GetMapping
    public Page list(@RequestParam Kind kind, @RequestParam(defaultValue = "0") int offset,
                     @RequestParam(defaultValue = "20") int limit, Principal operator) {
        if (offset < 0 || limit < 1 || limit > 100)
            throw new ExperimentDrafts.Problem(400, "Choose a nonnegative offset and a page size from 1 to 100.");
        String query = switch (kind) {
            case DRAFTS -> """
                SELECT d.id, t.name AS target_name, sc.name AS script_name, d.scenario_version AS script_version,
                       d.revision, 'DRAFT' AS status, d.updated_at AS listed_at
                FROM experiment_draft d
                JOIN registered_target t ON t.id = d.target_id
                LEFT JOIN scenario sc ON sc.id = d.scenario_id
                WHERE d.created_by = ?
                ORDER BY d.updated_at DESC, d.id DESC
                LIMIT ? OFFSET ?
                """;
            case SNAPSHOTS -> """
                SELECT s.id, s.inputs_json::jsonb #>> '{target,name}' AS target_name,
                       s.inputs_json::jsonb #>> '{scenario,script,name}' AS script_name,
                       (s.inputs_json::jsonb #>> '{scenario,script,version}')::integer AS script_version,
                       s.draft_revision AS revision, 'FINALIZED' AS status, s.created_at AS listed_at
                FROM experiment_input_snapshot s
                WHERE s.created_by = ?
                ORDER BY s.created_at DESC, s.id DESC
                LIMIT ? OFFSET ?
                """;
            case RUNS -> """
                SELECT r.id, s.inputs_json::jsonb #>> '{target,name}' AS target_name,
                       s.inputs_json::jsonb #>> '{scenario,script,name}' AS script_name,
                       (s.inputs_json::jsonb #>> '{scenario,script,version}')::integer AS script_version,
                       r.revision, r.status, r.created_at AS listed_at
                FROM experiment_run r
                JOIN experiment_input_snapshot s ON s.id = r.snapshot_id
                WHERE r.created_by = ?
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT ? OFFSET ?
                """;
        };
        var entries = db.query(query, (row, index) -> new Entry(row.getObject("id", UUID.class),
                row.getString("target_name"), row.getString("script_name"), row.getObject("script_version", Integer.class),
                row.getLong("revision"), row.getString("status"), row.getObject("listed_at", OffsetDateTime.class)),
                operator.getName(), limit + 1, offset);
        return new Page(entries.subList(0, Math.min(limit, entries.size())), entries.size() > limit);
    }
}
