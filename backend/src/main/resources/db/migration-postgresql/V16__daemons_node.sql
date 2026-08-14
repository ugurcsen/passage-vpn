-- Multi-node support: assign a daemon to a registered VPN node. NULL means the
-- local deployment; config files for remote-node daemons live on the remote
-- gateway, not in the local shared volume.

ALTER TABLE daemons ADD COLUMN node_id TEXT;
CREATE INDEX idx_daemons_node ON daemons (node_id);
