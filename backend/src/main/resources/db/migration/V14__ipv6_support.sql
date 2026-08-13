-- IPv6 (dual-stack) support: per-user static IPv6 for CCD `ifconfig-ipv6-push`,
-- optional IPv6 answers for DNS overrides (dnsmasq AAAA pins) and per-daemon
-- dual-stack addressing.

ALTER TABLE users ADD COLUMN static_ipv6 TEXT;
CREATE UNIQUE INDEX idx_users_static_ipv6 ON users (static_ipv6);

ALTER TABLE dns_records ADD COLUMN ipv6 TEXT;

ALTER TABLE daemons ADD COLUMN ipv6_enabled BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE daemons ADD COLUMN ipv6_subnet TEXT;
