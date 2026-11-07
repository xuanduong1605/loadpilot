ALTER TABLE experiment_attempt DROP CONSTRAINT experiment_attempt_worker_state_check;
ALTER TABLE experiment_attempt ADD CONSTRAINT experiment_attempt_worker_state_check
    CHECK (worker_state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','TERMINATED','UNREACHABLE'));
ALTER TABLE experiment_worker_observation DROP CONSTRAINT experiment_worker_observation_state_check;
ALTER TABLE experiment_worker_observation ADD CONSTRAINT experiment_worker_observation_state_check
    CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','TERMINATED','UNREACHABLE'));
