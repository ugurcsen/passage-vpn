const API_BASE = "/api";

const TOKEN_KEY = "opnl.access";
const REFRESH_KEY = "opnl.refresh";

export const tokenStore = {
  get access() {
    return localStorage.getItem(TOKEN_KEY);
  },
  get refresh() {
    return localStorage.getItem(REFRESH_KEY);
  },
  set: (access: string | null, refresh: string | null) => {
    if (access) localStorage.setItem(TOKEN_KEY, access);
    else localStorage.removeItem(TOKEN_KEY);
    if (refresh) localStorage.setItem(REFRESH_KEY, refresh);
    else localStorage.removeItem(REFRESH_KEY);
  },
  clear: () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
};

export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

let refreshing: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  const refresh = tokenStore.refresh;
  if (!refresh) return null;
  const res = await fetch(`${API_BASE}/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: refresh }),
  });
  if (!res.ok) {
    tokenStore.clear();
    return null;
  }
  const data = await res.json();
  tokenStore.set(data.accessToken, data.refreshToken ?? refresh);
  return data.accessToken;
}

/** Authenticated fetch wrapper: bearer token, automatic refresh on 401. */
export async function api<T = unknown>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const doFetch = (token: string | null): Promise<Response> => {
    const headers: Record<string, string> = {
      ...((options.headers as Record<string, string>) ?? {}),
    };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (options.body && !(options.body instanceof FormData)) {
      headers["Content-Type"] = "application/json";
    }
    return fetch(`${API_BASE}${path}`, { ...options, headers });
  };

  let res = await doFetch(tokenStore.access);
  if (res.status === 401 && !path.startsWith("/auth/")) {
    refreshing ??= refreshAccessToken().finally(() => {
      refreshing = null;
    });
    const fresh = await refreshing;
    if (fresh) res = await doFetch(fresh);
  }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    let code: string | undefined;
    try {
      const body = await res.json();
      message = body.message ?? message;
      code = body.code;
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, message, code);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/** Unauthenticated call (login, mfa, refresh, setup state). */
export function apiPublic<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  return fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...((options.headers as Record<string, string>) ?? {}),
    },
  }).then(async (res) => {
    if (!res.ok) {
      let message = `${res.status} ${res.statusText}`;
      try {
        const body = await res.json();
        message = body.message ?? message;
      } catch {
        /* ignore */
      }
      throw new ApiError(res.status, message);
    }
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
  });
}

export const endpoints = {
  me: "/auth/me",
  login: "/auth/login",
  mfa: "/auth/mfa",
  refresh: "/auth/refresh",
  logout: "/auth/logout",
  setupState: "/setup/state",
  setupWizard: "/setup/wizard",
  users: "/admin/users",
  groups: "/admin/groups",
  certs: "/admin/certs",
  profiles: "/admin/profiles",
  status: "/admin/status",
  settings: "/admin/settings",
  dashboard: "/admin/dashboard",
};
