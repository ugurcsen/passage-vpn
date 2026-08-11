import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, tokenStore } from "@/lib/api";

/** Builds a JWT-shaped token carrying the given `exp` claim (seconds). */
function makeJwt(exp: number): string {
  const enc = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${enc({ alg: "none" })}.${enc({ exp })}.${enc({})}`;
}

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
    vi.useRealTimers();
    tokenStore.clear();
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

  it("proactively refreshes the access token before it expires", async () => {
    vi.useFakeTimers();
    const nowSeconds = Math.floor(Date.now() / 1000);
    const firstAccess = makeJwt(nowSeconds + 120);
    const secondAccess = makeJwt(nowSeconds + 240);
    localStorage.clear();

    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === "/api/auth/refresh") {
        return Promise.resolve(
          new Response(JSON.stringify({ accessToken: secondAccess, refreshToken: "refresh-2" }), {
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
    });
    vi.stubGlobal("fetch", fetchMock);

    tokenStore.set(firstAccess, "refresh-1");
    expect(fetchMock).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(96_000 + 1_000);

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => url === "/api/auth/refresh");
    expect(refreshCalls).toHaveLength(1);
    expect(tokenStore.access).toBe(secondAccess);
    expect(tokenStore.refresh).toBe("refresh-2");
  });

  it("uses the proactively refreshed token for requests crossing the expiry boundary", async () => {
    vi.useFakeTimers();
    const nowSeconds = Math.floor(Date.now() / 1000);
    const firstAccess = makeJwt(nowSeconds + 120);
    const secondAccess = makeJwt(nowSeconds + 240);
    localStorage.clear();

    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === "/api/auth/refresh") {
        return Promise.resolve(
          new Response(JSON.stringify({ accessToken: secondAccess, refreshToken: "refresh-2" }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        );
      }
      return Promise.resolve(
        new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    tokenStore.set(firstAccess, "refresh-1");
    await vi.advanceTimersByTimeAsync(97_000);

    await api("/admin/status");

    const pollCall = fetchMock.mock.calls.find(([url]) => url === "/api/admin/status");
    const headers = pollCall?.[1] as RequestInit | undefined;
    const auth = (headers?.headers as Record<string, string> | undefined)?.Authorization;
    expect(auth).toBe(`Bearer ${secondAccess}`);
    const refreshCalls = fetchMock.mock.calls.filter(([url]) => url === "/api/auth/refresh");
    expect(refreshCalls).toHaveLength(1);
  });
});
