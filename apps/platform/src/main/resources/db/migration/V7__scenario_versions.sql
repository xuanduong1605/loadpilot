CREATE TABLE scenario (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    latest_version INTEGER NOT NULL DEFAULT 1 CHECK (latest_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE scenario_version (
    scenario_id UUID NOT NULL REFERENCES scenario(id),
    version INTEGER NOT NULL CHECK (version > 0),
    source TEXT NOT NULL CHECK (octet_length(source) BETWEEN 1 AND 65536),
    sha256 CHAR(64) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scenario_id, version)
);
