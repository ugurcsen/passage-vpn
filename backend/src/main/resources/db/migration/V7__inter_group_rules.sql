-- Inter-group connectivity rules: a rule may target another group's subnet as the
-- destination instead of a raw CIDR. The engine resolves the group to its allocated
-- subnet (static IP pool range or member static IPs) when rendering iptables.

ALTER TABLE access_rules ADD COLUMN dst_group_id TEXT;
CREATE INDEX idx_access_rules_dst_group ON access_rules (dst_group_id);
