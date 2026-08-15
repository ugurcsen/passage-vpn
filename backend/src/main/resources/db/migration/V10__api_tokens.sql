-- API tokens for automation: only the SHA-256 hash of the raw token is stored;
-- the plaintext value is shown once at creation time. A token always carries the
-- ADMIN role so scripted automation can act with full administrative privileges.

CREATE TABLE api_tokens (
    id           TEXT PRIMARY KEY,
    label        TEXT NOT NULL,
    token_hash   TEXT NOT NULL,
    prefix       TEXT NOT NULL,
    role         TEXT NOT NULL,
    expires_at   TIMESTAMP,
    created_by   TEXT,
    created_at   TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP
);
CREATE UNIQUE INDEX uq_api_tokens_hash ON api_tokens (token_hash);
