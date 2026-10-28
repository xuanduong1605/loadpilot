CREATE TABLE system_connection (
    target_id uuid PRIMARY KEY REFERENCES registered_target(id),
    revision bigint NOT NULL DEFAULT 1,
    kubernetes_url varchar(2048) NOT NULL,
    kubernetes_ca text NOT NULL,
    kubernetes_token text NOT NULL,
    prometheus_url varchar(2048) NOT NULL,
    prometheus_ca text NOT NULL,
    prometheus_token text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
