CREATE TABLE experiment_resource_recovery (
    preparation_id UUID PRIMARY KEY REFERENCES experiment_worker_preparation(id),
    run_id UUID NOT NULL REFERENCES experiment_run(id),
    known_uid VARCHAR(128) CHECK (known_uid ~ '^[A-Za-z0-9._:-]{1,128}$'),
    observed_sha256 CHAR(64),
    delete_operation_id UUID UNIQUE,
    delete_version VARCHAR(256),
    delete_outcome VARCHAR(16) CHECK (delete_outcome IN ('REQUESTED','ACKNOWLEDGED','UNCERTAIN','DENIED','CONFLICT')),
    absence_verified_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((delete_operation_id IS NULL) = (delete_version IS NULL)),
    CHECK ((delete_operation_id IS NULL) = (delete_outcome IS NULL)),
    CHECK (delete_operation_id IS NULL OR known_uid IS NOT NULL),
    CHECK (absence_verified_at IS NULL OR known_uid IS NOT NULL)
);
CREATE FUNCTION preserve_resource_recovery() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.run_id IS DISTINCT FROM NEW.run_id OR OLD.preparation_id IS DISTINCT FROM NEW.preparation_id
    OR (OLD.known_uid IS NOT NULL AND OLD.known_uid IS DISTINCT FROM NEW.known_uid)
    OR (OLD.delete_operation_id IS NOT NULL AND (OLD.delete_operation_id IS DISTINCT FROM NEW.delete_operation_id OR OLD.delete_version IS DISTINCT FROM NEW.delete_version))
    OR (OLD.absence_verified_at IS NOT NULL AND OLD.absence_verified_at IS DISTINCT FROM NEW.absence_verified_at) THEN
   RAISE EXCEPTION 'Recovery identity and deletion intent are immutable';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER experiment_resource_recovery_identity BEFORE UPDATE ON experiment_resource_recovery
 FOR EACH ROW EXECUTE FUNCTION preserve_resource_recovery();
ALTER TABLE experiment_run_monitor DROP CONSTRAINT experiment_run_monitor_outcome_check;
ALTER TABLE experiment_run_monitor ADD CONSTRAINT experiment_run_monitor_outcome_check CHECK (outcome IN ('WAITING','UNAVAILABLE','RESULT_SAVED','NO_WORKER','CLEANED_NO_RESULT'));
