CREATE TABLE scenario_approval (
    id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL,
    version INTEGER NOT NULL,
    sha256 CHAR(64) NOT NULL,
    validation_id UUID NOT NULL REFERENCES scenario_validation(id),
    policy_version VARCHAR(64) NOT NULL,
    parser_version VARCHAR(32) NOT NULL,
    approved_by VARCHAR(128) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (scenario_id, version) REFERENCES scenario_version(scenario_id, version),
    UNIQUE (scenario_id, version, sha256, policy_version, parser_version)
);
