ALTER TABLE experiment_attempt ADD COLUMN worker_uid VARCHAR(128);
ALTER TABLE experiment_attempt ADD COLUMN worker_state VARCHAR(16)
    CHECK (worker_state IN ('RUNNING','SUCCEEDED','FAILED','TERMINATED','UNREACHABLE'));
CREATE TABLE experiment_worker_observation (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES experiment_run(id),
    attempt_id UUID NOT NULL REFERENCES experiment_attempt(id),
    submit_operation_id UUID NOT NULL REFERENCES experiment_operation(id),
    expected_revision BIGINT NOT NULL CHECK (expected_revision > 0),
    worker_name VARCHAR(63) NOT NULL,
    worker_uid VARCHAR(128),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','FAILED','TERMINATED','UNREACHABLE')),
    all_workers_terminated BOOLEAN NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (worker_uid IS NOT NULL OR state='UNREACHABLE'),
    CHECK (NOT all_workers_terminated OR state IN ('SUCCEEDED','FAILED','TERMINATED'))
);
