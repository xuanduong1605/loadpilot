CREATE TABLE experiment_worker_stop_receipt (
    operation_id UUID PRIMARY KEY REFERENCES experiment_operation(id),
    run_id UUID NOT NULL REFERENCES experiment_run(id),
    job_uid VARCHAR(128) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('REQUESTED','ACCEPTED','NOT_FOUND','UNCERTAIN','UID_CONFLICT','DENIED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMPTZ,
    CHECK ((outcome='REQUESTED') = (acknowledged_at IS NULL))
);
