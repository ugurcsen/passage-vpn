-- Multi-node support: one row per registered VPN gateway node. The local
-- deployment is implicit and never stored; remote nodes register here so the
-- central backend can route status/kill/monitor requests per node.
-- PostgreSQL variant: BOOLEAN defaults use TRUE/FALSE literals.

CREATE TABLE openvpn_nodes (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL UNIQUE,
    mgmt_host     TEXT NOT NULL,
    mgmt_port_base INTEGER NOT NULL DEFAULT 7505,
    admin_ip      TEXT,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL,
    last_seen_at  TIMESTAMP
);
CREATE INDEX idx_openvpn_nodes_enabled ON openvpn_nodes (enabled);
