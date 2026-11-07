CREATE TABLE experiment_worker_preparation (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES experiment_execution_plan(id),
    plan_sha256 CHAR(64) NOT NULL CHECK (plan_sha256 ~ '^[0-9a-f]{64}$'),
    resource VARCHAR(8) NOT NULL CHECK (resource IN ('FILES','NETWORK','JOB')),
    namespace VARCHAR(63) NOT NULL,
    worker_name VARCHAR(63) NOT NULL,
    run_revision BIGINT NOT NULL CHECK (run_revision > 0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('REQUESTED','ACKNOWLEDGED','CONFLICT','DENIED','UNCERTAIN')),
    resource_uid VARCHAR(128),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMPTZ,
    UNIQUE (plan_id,resource),
    CHECK ((outcome='REQUESTED') = (acknowledged_at IS NULL)),
    CHECK ((outcome='ACKNOWLEDGED') = (resource_uid IS NOT NULL)),
    CHECK (resource_uid IS NULL OR resource_uid ~ '^[A-Za-z0-9._:-]{1,128}$')
);
