CREATE TABLE scenario_validation (
    id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL,
    version INTEGER NOT NULL,
    sha256 CHAR(64) NOT NULL,
    syntax_valid BOOLEAN NOT NULL,
    policy_accepted BOOLEAN NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    parser_version VARCHAR(32) NOT NULL,
    findings JSONB NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (scenario_id, version) REFERENCES scenario_version(scenario_id, version)
);
CREATE INDEX scenario_validation_version ON scenario_validation(scenario_id, version, created_at DESC, id);
