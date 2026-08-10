import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { AuthProvider, useAuth } from "@/hooks/useAuth";
import { tokenStore } from "@/lib/api";

const meBody = {
  id: "u1",
  username: "alice",
  role: "USER",
  mfaEnabled: false,
  banned: false,
  mustChangePassword: false,
  groups: [],
};

function wrap() {
  return ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("useAuth", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("loads the current user when a token exists", async () => {
    tokenStore.set("access", "refresh");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(meBody)));

    const { result } = renderHook(() => useAuth(), { wrapper: wrap() });

    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.user?.username).toBe("alice"));
    expect(result.current.loading).toBe(false);
  });

  it("login stores tokens and loads the user", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        json({ mfaRequired: false, accessToken: "a", refreshToken: "r" }),
      )
      .mockResolvedValueOnce(json(meBody));
    vi.stubGlobal("fetch", fetchMock);

    const { result } = renderHook(() => useAuth(), { wrapper: wrap() });
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.login("alice", "supersecret1");
    });

    expect(tokenStore.access).toBe("a");
    expect(result.current.user?.username).toBe("alice");
  });

  it("login with MFA required does not store tokens", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce(json({ mfaRequired: true, preAuthToken: "p" })),
    );

    const { result } = renderHook(() => useAuth(), { wrapper: wrap() });
    await waitFor(() => expect(result.current.loading).toBe(false));

    const res = await act(async () => result.current.login("alice", "supersecret1"));

    expect(res.mfaRequired).toBe(true);
    expect(tokenStore.access).toBeNull();
  });

  it("logout clears tokens and user", async () => {
    tokenStore.set("a", "r");
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(json(meBody))));

    const { result } = renderHook(() => useAuth(), { wrapper: wrap() });
    await waitFor(() => expect(result.current.user?.username).toBe("alice"));

    await act(async () => {
      await result.current.logout();
    });

    expect(tokenStore.access).toBeNull();
    expect(tokenStore.refresh).toBeNull();
    expect(result.current.user).toBeNull();
  });
});
