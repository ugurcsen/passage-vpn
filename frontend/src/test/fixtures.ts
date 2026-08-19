/**
 * Shared test fixtures for frontend page tests.
 *
 * Common mock objects used across multiple test files to eliminate duplication.
 */

import type { CurrentUser } from "@/hooks/useAuth";

export const fakeAdmin: CurrentUser = {
  id: "admin-1",
  username: "admin",
  fullName: "Admin User",
  email: "admin@example.com",
  role: "ADMIN",
  mfaEnabled: false,
  banned: false,
  mustChangePassword: false,
  groups: [],
};

export const fakeGroupAdmin: CurrentUser = {
  id: "ga-1",
  username: "groupadmin",
  fullName: "Group Admin",
  email: "ga@example.com",
  role: "GROUP_ADMIN",
  mfaEnabled: false,
  banned: false,
  mustChangePassword: false,
  groups: [],
};

export const fakeUser: CurrentUser = {
  id: "user-1",
  username: "alice",
  fullName: "Alice Smith",
  email: "alice@example.com",
  role: "USER",
  mfaEnabled: false,
  banned: false,
  mustChangePassword: false,
  groups: [],
};

export const fakeUserMfa: CurrentUser = {
  ...fakeUser,
  id: "user-2",
  username: "bob",
  fullName: "Bob Jones",
  mfaEnabled: true,
};
