import type { Role } from "@/hooks/useAuth";

/** Landing page per role: admins get the dashboard, group admins their user management,
 *  plain users the self-service portal. */
export function homePathFor(role: Role): string {
  switch (role) {
    case "ADMIN":
      return "/";
    case "GROUP_ADMIN":
      return "/users";
    case "USER":
      return "/portal";
  }
}

/** True when the given role is included in the allowed set (empty means all roles). */
export function canAccess(roles: Role[] | undefined, role: Role): boolean {
  return !roles || roles.includes(role);
}
