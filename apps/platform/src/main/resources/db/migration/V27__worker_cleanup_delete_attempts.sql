CREATE TABLE experiment_resource_delete_retry (
    operation_id UUID PRIMARY KEY,
    preparation_id UUID NOT NULL REFERENCES experiment_resource_recovery(preparation_id),
    attempt_number SMALLINT NOT NULL CHECK (attempt_number BETWEEN 2 AND 3),
    resource_version VARCHAR(256) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('REQUESTED','ACKNOWLEDGED','UNCERTAIN','DENIED','CONFLICT')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (preparation_id, attempt_number),
    UNIQUE (preparation_id, resource_version)
);

-- The prior application still reads and writes the first attempt in the recovery row.
CREATE VIEW experiment_resource_delete_attempt AS
SELECT delete_operation_id AS operation_id, preparation_id, 1::SMALLINT AS attempt_number,
       delete_version AS resource_version, delete_outcome AS outcome
FROM experiment_resource_recovery WHERE delete_operation_id IS NOT NULL
UNION ALL
SELECT operation_id, preparation_id, attempt_number, resource_version, outcome
FROM experiment_resource_delete_retry;

CREATE OR REPLACE FUNCTION preserve_resource_recovery() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.run_id IS DISTINCT FROM NEW.run_id OR OLD.preparation_id IS DISTINCT FROM NEW.preparation_id
       OR (OLD.known_uid IS NOT NULL AND OLD.known_uid IS DISTINCT FROM NEW.known_uid)
       OR (OLD.delete_operation_id IS NOT NULL AND (OLD.delete_operation_id IS DISTINCT FROM NEW.delete_operation_id OR OLD.delete_version IS DISTINCT FROM NEW.delete_version))
       OR (OLD.delete_outcome IS NOT NULL AND OLD.delete_outcome <> 'REQUESTED' AND OLD.delete_outcome IS DISTINCT FROM NEW.delete_outcome)
       OR (OLD.absence_verified_at IS NOT NULL AND OLD.absence_verified_at IS DISTINCT FROM NEW.absence_verified_at) THEN
        RAISE EXCEPTION 'Recovery identity, delete intent and final outcome are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION preserve_resource_delete_attempt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.outcome <> 'REQUESTED' OR NEW.outcome = 'REQUESTED'
       OR OLD.operation_id IS DISTINCT FROM NEW.operation_id OR OLD.preparation_id IS DISTINCT FROM NEW.preparation_id
       OR OLD.attempt_number IS DISTINCT FROM NEW.attempt_number OR OLD.resource_version IS DISTINCT FROM NEW.resource_version THEN
        RAISE EXCEPTION 'Cleanup delete intent and final outcome are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER resource_delete_attempt_immutable BEFORE UPDATE OR DELETE ON experiment_resource_delete_retry
    FOR EACH ROW EXECUTE FUNCTION preserve_resource_delete_attempt();
