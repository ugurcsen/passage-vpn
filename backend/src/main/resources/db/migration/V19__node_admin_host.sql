-- Multi-node profiles: an optional public admin host per node. When a daemon
-- assigned to this node has no adminHost of its own, connection profiles use
-- this host as the remote endpoint (falling back to the global OPNL_ADMIN_HOST).

ALTER TABLE openvpn_nodes ADD COLUMN admin_host TEXT;
