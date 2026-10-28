CREATE TABLE access_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor varchar(128),
    action varchar(32) NOT NULL,
    object_type varchar(32) NOT NULL,
    outcome varchar(32) NOT NULL
);
