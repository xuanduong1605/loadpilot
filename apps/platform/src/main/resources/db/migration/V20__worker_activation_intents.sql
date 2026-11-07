ALTER TABLE experiment_operation DROP CONSTRAINT experiment_operation_kind_check;
ALTER TABLE experiment_operation ADD CONSTRAINT experiment_operation_kind_check CHECK (kind IN ('SUBMIT','STOP','ACTIVATE'));
CREATE TABLE experiment_worker_activation (
    operation_id UUID PRIMARY KEY REFERENCES experiment_operation(id),
    plan_id UUID NOT NULL UNIQUE REFERENCES experiment_execution_plan(id),
    run_id UUID NOT NULL REFERENCES experiment_run(id),
    plan_sha256 CHAR(64) NOT NULL,
    job_uid VARCHAR(128) NOT NULL,
    resource_version VARCHAR(256) NOT NULL,
    patch_json TEXT NOT NULL CHECK (octet_length(patch_json)<=65536 AND jsonb_typeof(patch_json::jsonb)='array'),
    patch_sha256 CHAR(64) NOT NULL,
    run_revision BIGINT NOT NULL CHECK (run_revision>0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('REQUESTED','ACKNOWLEDGED','CONFLICT','DENIED','UNCERTAIN')),
    acknowledged_version VARCHAR(256),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMPTZ,
    CHECK ((outcome='REQUESTED')=(acknowledged_at IS NULL)),
    CHECK ((outcome='ACKNOWLEDGED')=(acknowledged_version IS NOT NULL)),
    CHECK (acknowledged_version IS NULL OR (length(acknowledged_version)>0 AND acknowledged_version<>resource_version))
);
