-- Groups (named "user_groups" to avoid the SQL reserved word GROUP).
-- PostgreSQL variant: BOOLEAN defaults use TRUE/FALSE literals.
CREATE TABLE user_groups (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    parent_id   TEXT,
    description TEXT,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT uq_groups_name UNIQUE (name)
);

-- Group membership (many-to-many).
CREATE TABLE group_members (
    group_id TEXT NOT NULL,
    user_id  TEXT NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

-- Per-user and per-group settings, stored as JSON strings.
CREATE TABLE user_settings (
    id            TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL,
    setting_key   TEXT NOT NULL,
    setting_value TEXT,
    CONSTRAINT uq_user_settings UNIQUE (user_id, setting_key)
);

CREATE TABLE group_settings (
    id            TEXT PRIMARY KEY,
    group_id      TEXT NOT NULL,
    setting_key   TEXT NOT NULL,
    setting_value TEXT,
    CONSTRAINT uq_group_settings UNIQUE (group_id, setting_key)
);

-- Rotating refresh tokens (store a hash, never the token itself).
CREATE TABLE refresh_tokens (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked    BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_group_members_user ON group_members (user_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
