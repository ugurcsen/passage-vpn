-- PostgreSQL variant: same schema as the SQLite migration (V20).
-- Group admin role: GROUP_ADMIN bound to root groups via group_admin_assignments;
-- RESELLER is removed and existing resellers are demoted to USER.

CREATE TABLE group_admin_assignments (
  user_id  VARCHAR(36) NOT NULL,
  group_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (user_id, group_id)
);

CREATE INDEX idx_group_admin_assignments_group ON group_admin_assignments (group_id);

UPDATE users SET role = 'USER' WHERE role = 'RESELLER';
