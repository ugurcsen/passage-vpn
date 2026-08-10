import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, tokenStore } from "@/lib/api";

describe("api token refresh", () => {
  const access = "access-1";
  const refresh = "refresh-1";

  beforeEach(() => {
    localStorage.clear();
    tokenStore.set(access, refresh);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("returns data on success", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    const data = await api<{ ok: boolean }>("/admin/users");
    expect(data.ok).toBe(true);
  });

  it("refreshes once on 401 and retries", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(null, {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ accessToken: "access-2", refreshToken: "refresh-2" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ done: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const data = await api<{ done: boolean }>("/admin/users");

    expect(data.done).toBe(true);
    expect(tokenStore.access).toBe("access-2");
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const refreshCall = fetchMock.mock.calls[1] as [string];
    expect(refreshCall[0]).toBe("/api/auth/refresh");
  });

  it("concurrent 401s share a single refresh call", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((url: string) => {
        if (url === "/api/auth/refresh") {
          return Promise.resolve(
            new Response(JSON.stringify({ accessToken: "access-2", refreshToken: "refresh-2" }), {
              status: 200,
              headers: { "Content-Type": "application/json" },
            }),
          );
        }
        return Promise.resolve(
          new Response(JSON.stringify({ v: 1 }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        );
      })
      // force a 401 on the first two non-refresh requests
      .mockResolvedValueOnce(
        new Response(null, { status: 401, headers: { "Content-Type": "application/json" } }),
      )
      .mockResolvedValueOnce(
        new Response(null, { status: 401, headers: { "Content-Type": "application/json" } }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([api("/admin/users"), api("/admin/users")]);

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => url === "/api/auth/refresh");
    expect(refreshCalls).toHaveLength(1);
  });

  it("throws ApiError when refresh fails and clears tokens", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ message: "nope" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ message: "Refresh token invalid" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(api("/admin/users")).rejects.toBeInstanceOf(ApiError);
    expect(tokenStore.access).toBeNull();
    expect(tokenStore.refresh).toBeNull();
  });

  it("returns undefined for an empty 200 body (void endpoints)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(null, {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    const data = await api("/admin/users/u1/reset-password", { method: "POST" });
    expect(data).toBeUndefined();
  });

  it("surfaces ApiError with code from error body", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Rule not found", code: "rule_not_found" }), {
          status: 404,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    const err = await api("/admin/rules/x").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(404);
    expect((err as ApiError).code).toBe("rule_not_found");
  });

  it("does not retry on 401 for auth endpoints", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ message: "invalid" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(api("/auth/me")).rejects.toBeInstanceOf(ApiError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
