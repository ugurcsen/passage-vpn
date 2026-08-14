-- Node security hardening: every registered gateway must carry a management
-- interface password (the local deployment reads OPNL_OPENVPN_MGMT_PASSWORD),
-- and the last seen source IP is tracked so heartbeat/registration IP pinning
-- can be enforced when the node has an admin IP set.

ALTER TABLE openvpn_nodes ADD COLUMN mgmt_password TEXT;
ALTER TABLE openvpn_nodes ADD COLUMN last_seen_ip TEXT;
