ALTER TABLE experiment_draft ADD COLUMN created_by VARCHAR(128);

-- The first successful save identifies the creator. Later edits do not transfer ownership.
UPDATE experiment_draft AS draft
SET created_by = first_save.actor
FROM (
    SELECT DISTINCT ON (object_id) object_id, actor
    FROM access_audit
    WHERE action = 'EXPERIMENT_DRAFT_SAVED' AND object_type = 'EXPERIMENT' AND outcome = 'SUCCESS'
    ORDER BY object_id, id
) AS first_save
WHERE draft.id = first_save.object_id;

CREATE INDEX experiment_draft_library ON experiment_draft(created_by, updated_at DESC, id DESC);
CREATE INDEX experiment_snapshot_library ON experiment_input_snapshot(created_by, created_at DESC, id DESC);
CREATE INDEX experiment_run_library ON experiment_run(created_by, created_at DESC, id DESC);
