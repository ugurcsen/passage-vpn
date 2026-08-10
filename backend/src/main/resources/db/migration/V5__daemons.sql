-- Multi-daemon support: one row per configurable OpenVPN daemon (port 0 = primary).

CREATE TABLE daemons (
    id                       TEXT PRIMARY KEY,
    daemon_index             INTEGER NOT NULL,
    name                     TEXT,
    port                     INTEGER NOT NULL,
    proto                    TEXT NOT NULL,
    subnet                   TEXT NOT NULL,
    subnet_mask              TEXT NOT NULL,
    dns_servers              TEXT,
    domain                   TEXT,
    extra_routes             TEXT,
    full_tunnel              BOOLEAN NOT NULL DEFAULT 1,
    client_cert_not_required BOOLEAN NOT NULL DEFAULT 0,
    auth_user_pass           BOOLEAN NOT NULL DEFAULT 1,
    admin_host               TEXT,
    enabled                  BOOLEAN NOT NULL DEFAULT 1,
    created_at               TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uq_daemons_index ON daemons (daemon_index);
