-- DNS overrides: admin-defined hostname -> IPv4 records served authoritatively
-- by the container's dnsmasq, so VPN clients resolve internal server names only
-- while connected (public DNS does not know these names -> NXDOMAIN off-VPN).
-- GLOBAL records apply to all clients; GROUP/USER-scoped records are resolved
-- for everyone over the shared resolver but only the target scope may reach the
-- address (enforced per-client by RuleEngine scope denies).

CREATE TABLE dns_records (
  id TEXT PRIMARY KEY,
  hostname TEXT NOT NULL,
  ipv4 TEXT NOT NULL,
  scope TEXT NOT NULL DEFAULT 'GLOBAL',
  scope_id TEXT,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_dns_records_hostname ON dns_records (hostname);
CREATE INDEX idx_dns_records_scope ON dns_records (scope, scope_id);
