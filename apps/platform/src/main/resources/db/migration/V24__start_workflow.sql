CREATE TABLE experiment_start_workflow (
    run_id UUID PRIMARY KEY REFERENCES experiment_run(id),
    actor VARCHAR(128) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','DISPATCHING','OBSERVING','HALTED','FAILED','RECOVERY_REQUIRED')),
    reason VARCHAR(32) NOT NULL DEFAULT 'WAITING' CHECK (reason IN ('WAITING','OBSERVING','STOP_REQUESTED','START_REJECTED','RECONCILIATION_REQUIRED')),
    plan_id UUID UNIQUE REFERENCES experiment_execution_plan(id),
    admission_json TEXT,
    admission_sha256 CHAR(64) CHECK (admission_sha256 ~ '^[0-9a-f]{64}$'),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((plan_id IS NULL) = (admission_json IS NULL)),
    CHECK ((admission_json IS NULL) = (admission_sha256 IS NULL)),
    CHECK (admission_json IS NULL OR (octet_length(admission_json)<=2048 AND jsonb_typeof(admission_json::jsonb)='object'))
);
CREATE INDEX experiment_start_workflow_due ON experiment_start_workflow(next_at)
    WHERE state IN ('QUEUED','DISPATCHING');
CREATE FUNCTION preserve_start_admission() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.actor IS DISTINCT FROM NEW.actor OR OLD.run_id IS DISTINCT FROM NEW.run_id OR
       (OLD.plan_id IS NOT NULL AND (OLD.plan_id IS DISTINCT FROM NEW.plan_id OR OLD.admission_json IS DISTINCT FROM NEW.admission_json OR OLD.admission_sha256 IS DISTINCT FROM NEW.admission_sha256)) THEN
        RAISE EXCEPTION 'Start admission is immutable once recorded';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER experiment_start_admission BEFORE UPDATE ON experiment_start_workflow
    FOR EACH ROW EXECUTE FUNCTION preserve_start_admission();
ALTER TABLE experiment_run DROP CONSTRAINT experiment_run_stop_reason_check;
ALTER TABLE experiment_run ADD CONSTRAINT experiment_run_stop_reason_check CHECK (stop_reason IN ('USER','DEADLINE','STARTUP','EXECUTION'));
