CREATE TABLE experiment_execution_plan (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES experiment_run(id),
    attempt_id UUID NOT NULL UNIQUE REFERENCES experiment_attempt(id),
    submit_operation_id UUID NOT NULL UNIQUE REFERENCES experiment_operation(id),
    run_revision BIGINT NOT NULL CHECK (run_revision > 0),
    sha256 CHAR(64) NOT NULL,
    plan_json TEXT NOT NULL CHECK (jsonb_typeof(plan_json::jsonb) = 'object' AND octet_length(plan_json) <= 524288),
    prepared_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
