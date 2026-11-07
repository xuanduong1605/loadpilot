CREATE TABLE experiment_run (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL UNIQUE REFERENCES experiment_input_snapshot(id),
    target_id UUID NOT NULL REFERENCES registered_target(id),
    request_key UUID NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED','SUBMITTING','RUNNING','STOPPING','COMPLETED','FAILED','CANCELLED','RECOVERY_REQUIRED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    deadline_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (deadline_at > created_at)
);
CREATE UNIQUE INDEX experiment_run_active_target ON experiment_run(target_id)
    WHERE status IN ('QUEUED','SUBMITTING','RUNNING','STOPPING','RECOVERY_REQUIRED');
CREATE TABLE experiment_attempt (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES experiment_run(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 100),
    shard_id UUID NOT NULL UNIQUE,
    worker_name VARCHAR(63) NOT NULL UNIQUE,
    maximum_seconds INTEGER NOT NULL CHECK (maximum_seconds BETWEEN 1 AND 3960),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id,attempt_number)
);
CREATE TABLE experiment_operation (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES experiment_attempt(id),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('SUBMIT','STOP')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (attempt_id,kind)
);
