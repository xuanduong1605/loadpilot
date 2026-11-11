ALTER TABLE experiment_attempt ADD COLUMN next_http_capture_at TIMESTAMPTZ;

CREATE TABLE experiment_worker_http_window (
    attempt_id UUID NOT NULL REFERENCES experiment_attempt(id),
    plan_id UUID NOT NULL REFERENCES experiment_execution_plan(id),
    job_uid VARCHAR(128) NOT NULL CHECK (job_uid ~ '^[A-Za-z0-9._:-]{1,128}$'),
    pod_uid VARCHAR(128) NOT NULL CHECK (pod_uid ~ '^[A-Za-z0-9._:-]{1,128}$'),
    window_from TIMESTAMPTZ NOT NULL,
    window_until TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count BETWEEN 1 AND 10000),
    error_count INTEGER NOT NULL CHECK (error_count BETWEEN 0 AND request_count),
    requests_per_second DOUBLE PRECISION NOT NULL CHECK (requests_per_second > 0 AND requests_per_second < 'Infinity'),
    p95_latency_ms DOUBLE PRECISION NOT NULL CHECK (p95_latency_ms >= 0 AND p95_latency_ms < 'Infinity'),
    error_ratio DOUBLE PRECISION NOT NULL CHECK (error_ratio BETWEEN 0 AND 1),
    source VARCHAR(100) NOT NULL,
    algorithm VARCHAR(64) NOT NULL,
    PRIMARY KEY (attempt_id, window_until),
    CHECK (window_from < window_until AND window_until <= collected_at AND window_until - window_from <= INTERVAL '10 seconds')
);

CREATE TRIGGER worker_http_window_immutable BEFORE UPDATE OR DELETE ON experiment_worker_http_window
    FOR EACH ROW EXECUTE FUNCTION prevent_worker_result_change();
