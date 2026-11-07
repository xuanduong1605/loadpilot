ALTER TABLE experiment_attempt ADD COLUMN submission_started_at TIMESTAMPTZ;
ALTER TABLE experiment_attempt ADD COLUMN startup_observed_at TIMESTAMPTZ;
ALTER TABLE experiment_attempt ADD CONSTRAINT experiment_attempt_startup_clock
    CHECK (startup_observed_at IS NULL OR submission_started_at IS NULL OR startup_observed_at >= submission_started_at);

CREATE FUNCTION preserve_attempt_clock() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.submission_started_at IS NOT NULL AND NEW.submission_started_at IS DISTINCT FROM OLD.submission_started_at)
       OR (OLD.startup_observed_at IS NOT NULL AND NEW.startup_observed_at IS DISTINCT FROM OLD.startup_observed_at) THEN
        RAISE EXCEPTION 'Attempt clocks are immutable once recorded';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER experiment_attempt_clock BEFORE UPDATE ON experiment_attempt
    FOR EACH ROW EXECUTE FUNCTION preserve_attempt_clock();

ALTER TABLE experiment_run DROP CONSTRAINT experiment_run_stop_reason_check;
ALTER TABLE experiment_run ADD CONSTRAINT experiment_run_stop_reason_check CHECK (stop_reason IN ('USER','DEADLINE','STARTUP'));
