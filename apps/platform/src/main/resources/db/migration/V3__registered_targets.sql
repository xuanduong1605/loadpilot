CREATE TABLE registered_target (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL,
    base_url varchar(2048) NOT NULL,
    environment varchar(16) NOT NULL CHECK (environment IN ('DEVELOPMENT','STAGING','PRODUCTION')),
    namespace varchar(63) NOT NULL,
    deployment varchar(253) NOT NULL,
    container varchar(63) NOT NULL,
    credential_ref varchar(100),
    monitoring_ref varchar(100),
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at timestamptz
);
CREATE UNIQUE INDEX registered_target_active_name ON registered_target (lower(name)) WHERE archived_at IS NULL;
ALTER TABLE access_audit ADD COLUMN object_id uuid;
