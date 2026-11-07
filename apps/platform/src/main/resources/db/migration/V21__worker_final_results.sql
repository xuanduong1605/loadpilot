CREATE TABLE experiment_worker_result (
    attempt_id UUID PRIMARY KEY REFERENCES experiment_attempt(id),
    run_id UUID NOT NULL UNIQUE REFERENCES experiment_run(id),
    plan_id UUID NOT NULL REFERENCES experiment_execution_plan(id),
    job_uid VARCHAR(128) NOT NULL,
    pod_uid VARCHAR(128) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    result_json TEXT NOT NULL CHECK (jsonb_typeof(result_json::jsonb) = 'object' AND octet_length(result_json) <= 8192),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE FUNCTION prevent_worker_result_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Worker results are immutable';
END;
$$;
CREATE TRIGGER worker_result_immutable BEFORE UPDATE OR DELETE ON experiment_worker_result
FOR EACH ROW EXECUTE FUNCTION prevent_worker_result_change();
