-- Group admin role: introduces the GROUP_ADMIN role bound to one or more root
-- groups via a join table (group_admin_assignments). RESELLER is removed and
-- existing resellers are demoted to USER (least privilege); admins re-grant
-- the role explicitly.

CREATE TABLE group_admin_assignments (
  user_id  TEXT NOT NULL,
  group_id TEXT NOT NULL,
  PRIMARY KEY (user_id, group_id)
);

CREATE INDEX idx_group_admin_assignments_group ON group_admin_assignments (group_id);

UPDATE users SET role = 'USER' WHERE role = 'RESELLER';
