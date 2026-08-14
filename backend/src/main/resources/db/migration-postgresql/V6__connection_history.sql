-- Connection history: one row per VPN session, written by the internal connect/disconnect
-- callbacks and finalized with byte counters from the management interface.

CREATE TABLE connection_logs (
    id              TEXT PRIMARY KEY,
    username        TEXT NOT NULL,
    common_name     TEXT NOT NULL,
    virtual_ip      TEXT,
    remote_ip       TEXT,
    daemon_name     TEXT,
    connected_at    TIMESTAMP NOT NULL,
    disconnected_at TIMESTAMP,
    bytes_in        BIGINT NOT NULL DEFAULT 0,
    bytes_out       BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL
);
CREATE INDEX idx_connection_logs_connected_at ON connection_logs (connected_at);
CREATE INDEX idx_connection_logs_username ON connection_logs (username);
