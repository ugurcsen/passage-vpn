-- Admin and authentication audit trail: one row per recorded action, written by the audit
-- service alongside the mutation and pruned after the audit retention window.

CREATE TABLE audit_logs (
    id          TEXT PRIMARY KEY,
    actor_id    TEXT,
    actor_name  TEXT,
    action      TEXT NOT NULL,
    category    TEXT NOT NULL,
    target_id   TEXT,
    target_type TEXT,
    detail      TEXT,
    ip          TEXT,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_id);
CREATE INDEX idx_audit_logs_action ON audit_logs (action);
