-- Users (core fields; extended in later phases).
-- PostgreSQL variant: BOOLEAN defaults use TRUE/FALSE literals.
CREATE TABLE users (
    id             TEXT      PRIMARY KEY,
    username       TEXT      NOT NULL,
    password_hash  TEXT,
    full_name      TEXT,
    email          TEXT,
    role           TEXT      NOT NULL DEFAULT 'USER',
    mfa_secret     TEXT,
    mfa_enabled    BOOLEAN   NOT NULL DEFAULT FALSE,
    banned         BOOLEAN   NOT NULL DEFAULT FALSE,
    locked_until   TIMESTAMP,
    failed_attempts INTEGER  NOT NULL DEFAULT 0,
    created_at     TIMESTAMP NOT NULL,
    last_login_at  TIMESTAMP,
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- Server-level settings stored as JSON strings (portable to PostgreSQL).
CREATE TABLE server_settings (
    setting_key   TEXT PRIMARY KEY,
    setting_value TEXT
);

CREATE INDEX idx_users_username ON users (username);
