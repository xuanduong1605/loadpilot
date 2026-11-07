ALTER TABLE experiment_draft ADD COLUMN scenario_id UUID;
ALTER TABLE experiment_draft ADD COLUMN scenario_version INTEGER;
ALTER TABLE experiment_draft ADD CONSTRAINT experiment_draft_scenario_pair CHECK ((scenario_id IS NULL) = (scenario_version IS NULL));
ALTER TABLE experiment_draft ADD FOREIGN KEY (scenario_id,scenario_version) REFERENCES scenario_version(scenario_id,version);
CREATE TABLE experiment_input_snapshot (
    id UUID PRIMARY KEY,
    draft_id UUID NOT NULL REFERENCES experiment_draft(id),
    draft_revision BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    inputs_json TEXT NOT NULL CHECK (jsonb_typeof(inputs_json::jsonb) = 'object'),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (draft_id,draft_revision)
);
