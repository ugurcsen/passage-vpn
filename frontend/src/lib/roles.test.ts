import { describe, expect, it } from "vitest";
import { homePathFor, canAccess } from "@/lib/roles";

describe("homePathFor", () => {
  it("returns / for ADMIN", () => {
    expect(homePathFor("ADMIN")).toBe("/");
  });

  it("returns /users for GROUP_ADMIN", () => {
    expect(homePathFor("GROUP_ADMIN")).toBe("/users");
  });

  it("returns /portal for USER", () => {
    expect(homePathFor("USER")).toBe("/portal");
  });
});

describe("canAccess", () => {
  it("returns true when roles is undefined", () => {
    expect(canAccess(undefined, "USER")).toBe(true);
  });

  it("returns true when role is in the allowed set", () => {
    expect(canAccess(["ADMIN", "USER"], "ADMIN")).toBe(true);
  });

  it("returns false when role is not in the allowed set", () => {
    expect(canAccess(["ADMIN"], "USER")).toBe(false);
  });
});
