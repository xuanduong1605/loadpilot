CREATE TABLE users (
    username varchar(128) PRIMARY KEY CHECK (username = trim(username) AND length(username) > 0),
    password varchar(60) NOT NULL CHECK (length(password) = 60 AND left(password, 4) IN ('$2a$', '$2b$', '$2y$')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE authorities (
    username varchar(128) NOT NULL REFERENCES users(username),
    authority varchar(50) NOT NULL CHECK (authority = 'ROLE_OPERATOR'),
    PRIMARY KEY (username, authority)
);
