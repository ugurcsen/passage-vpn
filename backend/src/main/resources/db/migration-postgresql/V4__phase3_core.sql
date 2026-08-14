-- Phase 3: static IPs, certificate bookkeeping, access rules, profile tokens.
-- PostgreSQL variant: BOOLEAN defaults use TRUE/FALSE literals.

ALTER TABLE users ADD COLUMN static_ip TEXT;
CREATE UNIQUE INDEX idx_users_static_ip ON users (static_ip);

CREATE TABLE certificates (
    id          TEXT PRIMARY KEY,
    common_name TEXT NOT NULL,
    user_id     TEXT,
    status      TEXT NOT NULL,
    serial      TEXT,
    issued_at   TIMESTAMP,
    expires_at  TIMESTAMP,
    revoked_at  TIMESTAMP,
    CONSTRAINT uq_certificates_cn UNIQUE (common_name)
);
CREATE INDEX idx_certificates_user ON certificates (user_id);

CREATE TABLE access_rules (
    id          TEXT PRIMARY KEY,
    target_type TEXT NOT NULL,
    target_id   TEXT,
    action      TEXT NOT NULL,
    protocol    TEXT,
    src_cidr    TEXT,
    dst_cidr    TEXT,
    dst_port    INTEGER,
    priority    INTEGER NOT NULL DEFAULT 100,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX idx_access_rules_target ON access_rules (target_type, target_id);

CREATE TABLE profile_tokens (
    id           TEXT PRIMARY KEY,
    token        TEXT NOT NULL,
    user_id      TEXT,
    profile_type TEXT NOT NULL,
    expires_at   TIMESTAMP,
    uses_left    INTEGER,
    created_at   TIMESTAMP NOT NULL,
    revoked      BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_profile_tokens_token ON profile_tokens (token);
