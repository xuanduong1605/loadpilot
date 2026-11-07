ALTER TABLE experiment_run ADD COLUMN stop_reason VARCHAR(16)
    CHECK (stop_reason IN ('USER', 'DEADLINE'));

CREATE TABLE experiment_run_monitor (
    run_id UUID PRIMARY KEY REFERENCES experiment_run(id),
    next_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempts BIGINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    failures INTEGER NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 6),
    outcome VARCHAR(24) NOT NULL DEFAULT 'WAITING'
        CHECK (outcome IN ('WAITING', 'UNAVAILABLE', 'RESULT_SAVED', 'NO_WORKER')),
    finished BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at TIMESTAMPTZ,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX experiment_run_monitor_due ON experiment_run_monitor(next_at) WHERE NOT finished;
