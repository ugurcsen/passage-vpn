-- PostgreSQL variant: same columns as the SQLite migration (V18).
-- Node security hardening: management interface password + last seen source IP.

ALTER TABLE openvpn_nodes ADD COLUMN mgmt_password TEXT;
ALTER TABLE openvpn_nodes ADD COLUMN last_seen_ip TEXT;
