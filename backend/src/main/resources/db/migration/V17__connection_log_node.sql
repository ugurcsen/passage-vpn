-- Record which VPN node a session ran on (NULL = local deployment), so
-- connection history stays correct in a multi-node setup.

ALTER TABLE connection_logs ADD COLUMN node_id TEXT;
CREATE INDEX idx_connection_logs_node ON connection_logs (node_id);
