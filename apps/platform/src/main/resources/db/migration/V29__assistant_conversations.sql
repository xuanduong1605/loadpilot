CREATE TABLE assistant_conversation (
    id uuid PRIMARY KEY,
    operator_name varchar(128) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    title varchar(80) NOT NULL,
    turn_count integer NOT NULL DEFAULT 0 CHECK (turn_count BETWEEN 0 AND 50),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX assistant_conversation_history ON assistant_conversation (operator_name, updated_at DESC, id DESC);

CREATE TABLE assistant_turn (
    conversation_id uuid NOT NULL REFERENCES assistant_conversation(id) ON DELETE CASCADE,
    turn_number integer NOT NULL CHECK (turn_number BETWEEN 1 AND 50),
    request_id uuid NOT NULL,
    prompt varchar(2000) NOT NULL,
    task varchar(20) NOT NULL,
    record_id uuid,
    reply_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, turn_number),
    UNIQUE (conversation_id, request_id)
);
