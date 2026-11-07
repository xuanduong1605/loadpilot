CREATE TABLE experiment_draft (
    id UUID PRIMARY KEY,
    target_id UUID NOT NULL REFERENCES registered_target(id),
    settings JSONB NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX experiment_draft_updated ON experiment_draft(updated_at DESC, id);
