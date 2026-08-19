import type { Role } from "@/hooks/useAuth";

export interface UserRow {
  id: string;
  username: string;
  fullName?: string;
  email?: string;
  role: Role;
  mfaEnabled: boolean;
  mfaRequired?: boolean;
  banned: boolean;
  mustChangePassword: boolean;
  groups: string[];
  adminGroupIds?: string[];
  adminGroupNames?: string[];
  createdAt?: string;
  lastLoginAt?: string;
  staticIp?: string;
  staticIpv6?: string;
}

export interface GroupRow {
  id: string;
  name: string;
  description?: string;
  parentId?: string;
  memberCount: number;
}

export interface DeleteOptions {
  deleteAccessRules: boolean;
  clearCcd: boolean;
}

export const EMPTY_DELETE_OPTIONS: DeleteOptions = {
  deleteAccessRules: false,
  clearCcd: false,
};

export interface UserForm {
  username: string;
  password: string;
  fullName: string;
  email: string;
  role: Role;
  groupIds: string[];
  adminGroupIds: string[];
}

export const EMPTY_FORM: UserForm = {
  username: "",
  password: "",
  fullName: "",
  email: "",
  role: "USER",
  groupIds: [],
  adminGroupIds: [],
};

export function formatDateTime(iso?: string) {
  if (!iso) return "\u2014";
  return new Date(iso).toLocaleString();
}
