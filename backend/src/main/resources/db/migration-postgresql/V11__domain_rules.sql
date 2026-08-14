-- Domain-based access control: a rule may target a domain name instead of a
-- raw CIDR or a group's subnet. The engine resolves the domain to its current
-- IPv4 addresses when rendering per-client iptables rules, and the backend
-- renders matching pinning entries into the container's dnsmasq config so
-- clients always resolve the domain to the pinned addresses the firewall knows.

ALTER TABLE access_rules ADD COLUMN dst_domain TEXT;
CREATE INDEX idx_access_rules_dst_domain ON access_rules (dst_domain);
