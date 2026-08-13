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
    if (access) {
      localStorage.setItem(TOKEN_KEY, access);
      scheduleProactiveRefresh();
    } else {
      localStorage.removeItem(TOKEN_KEY);
      cancelProactiveRefresh();
    }
    if (refresh) localStorage.setItem(REFRESH_KEY, refresh);
    else localStorage.removeItem(REFRESH_KEY);
  },
  clear: () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    cancelProactiveRefresh();
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
let proactiveTimer: number | null = null;

/** Fraction of the access-token TTL at which a silent refresh is scheduled. */
const PROACTIVE_REFRESH_FRACTION = 0.8;

/** Decodes the JWT `exp` claim (seconds since epoch) to schedule proactive refreshes.
 *  The signature is not verified here; malformed/non-JWT tokens simply disable the schedule. */
function accessTokenExpiry(token: string | null): number | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
    const payload = JSON.parse(atob(padded)) as { exp?: unknown };
    return typeof payload.exp === "number" ? payload.exp : null;
  } catch {
    return null;
  }
}

/** Refreshes the access token shortly before it expires so background polls never fire with a
 *  known-expired token (avoids the 401 burst around the expiry boundary). */
function scheduleProactiveRefresh(): void {
  if (proactiveTimer !== null) {
    window.clearTimeout(proactiveTimer);
    proactiveTimer = null;
  }
  const exp = accessTokenExpiry(tokenStore.access);
  if (exp === null) return;
  const ttlMs = exp * 1000 - Date.now();
  const delayMs = ttlMs * PROACTIVE_REFRESH_FRACTION;
  if (delayMs <= 0) return;
  proactiveTimer = window.setTimeout(() => {
    proactiveTimer = null;
    void refreshNow();
  }, delayMs);
}

function cancelProactiveRefresh(): void {
  if (proactiveTimer !== null) {
    window.clearTimeout(proactiveTimer);
    proactiveTimer = null;
  }
}

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

/** Refreshes the token exactly once across concurrent callers (401 retries + proactive timer). */
function refreshNow(): Promise<string | null> {
  refreshing ??= refreshAccessToken().finally(() => {
    refreshing = null;
  });
  return refreshing;
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
    const fresh = await refreshNow();
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
  return (await parseJson(res)) as T;
}

/** Parses a JSON body, returning undefined for empty (200 without content) responses. */
async function parseJson<T>(res: Response): Promise<T> {
  const text = await res.text();
  if (!text.trim()) return undefined as T;
  return JSON.parse(text) as T;
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
    return res.status === 204 ? (undefined as T) : parseJson(res);
  });
}

/** Copies text to the clipboard, falling back to a hidden-textarea execCommand for non-secure
 *  (HTTP) contexts where the async Clipboard API is unavailable. Returns whether it succeeded. */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      /* fall through to legacy path */
    }
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  let ok = false;
  try {
    ok = document.execCommand("copy");
  } catch {
    ok = false;
  }
  textarea.remove();
  return ok;
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
  rules: "/admin/rules",
  daemons: "/admin/daemons",
  profileTokens: "/admin/profile-tokens",
  portalProfiles: "/portal/profiles",
  portalAccountMfaSetup: "/portal/account/mfa/setup",
  portalAccountMfaEnable: "/portal/account/mfa/enable",
  portalAccountMfaDisable: "/portal/account/mfa/disable",
  portalAccountPassword: "/portal/account/password",
  connections: "/admin/connections",
  status: "/admin/status",
  settings: "/admin/settings",
  dashboard: "/admin/dashboard",
  connectionLogs: "/admin/connection-logs",
  system: "/admin/system",
  monitor: "/admin/monitor",
  auditLogs: "/admin/audit-logs",
  apiTokens: "/admin/api-tokens",
  publicBrand: "/public/brand",
  configReport: "/admin/config-report",
  backups: "/admin/backups",
};

export type ProfileType = "USER_LOCKED" | "AUTO_LOGIN" | "SERVER_LOCKED" | "GENERIC";

export interface OvpnFile {
  filename: string;
  content: string;
}

export interface Daemon {
  id: string;
  daemonIndex: number;
  name: string | null;
  port: number;
  proto: "udp" | "tcp";
  subnet: string;
  subnetMask: string;
  dnsServers: string[];
  domain: string | null;
  extraRoutes: string[];
  fullTunnel: boolean;
  clientCertNotRequired: boolean;
  authUserPass: boolean;
  adminHost: string | null;
  enabled: boolean;
  primary: boolean;
  createdAt: string;
  /** True when the daemon reports a DCO-capable data channel; null until polled. */
  dco?: boolean | null;
}

/** Health view of a single OpenVPN daemon (live status). */
export interface DaemonHealth {
  index: number;
  name: string | null;
  port: number;
  proto: "udp" | "tcp";
  enabled: boolean;
  configPresent: boolean;
  mgmtReachable: boolean;
  /** True when the daemon reports a DCO-capable data channel in its management TITLE. */
  dco?: boolean | null;
}

/** Snapshot returned by the live status endpoint. */
export interface ServerStatus {
  brand: string;
  version: string;
  uptimeSeconds: number;
  activeConnections: number;
  daemons: DaemonHealth[];
}

/** Active VPN session as tracked from connect/disconnect events. */
export interface VpnConnection {
  username: string | null;
  commonName: string;
  virtualIp: string | null;
  remoteIp: string | null;
  daemonName: string | null;
  connectedAt: string;
  /** Cumulative byte counters from the management interface (present when live). */
  bytesIn?: number | null;
  bytesOut?: number | null;
  bytesInPerSec?: number | null;
  bytesOutPerSec?: number | null;
}

/** Aggregate counts and recent activity for the dashboard. */
export interface DashboardStats {
  users: number;
  groups: number;
  activeCertificates: number;
  activeConnections: number;
  runningDaemons: number;
  totalDaemons: number;
  recentConnections: VpnConnection[];
}

/** One sample of the rolling traffic history ring. */
export interface TrafficPoint {
  at: string;
  bytesInPerSec: number;
  bytesOutPerSec: number;
  activeConnections: number;
}

/** Host resource usage reported by the backend system endpoint. */
export interface SystemInfo {
  cpuLoadPercent: number;
  totalMemory: number;
  freeMemory: number;
  diskTotal: number;
  diskFree: number;
  availableProcessors: number;
}

/** Full monitor payload pushed over /ws/status and served by /admin/monitor. */
export interface MonitorSnapshot {
  at: string;
  connections: VpnConnection[];
  daemons: DaemonHealth[];
  bytesInPerSec: number;
  bytesOutPerSec: number;
  activeConnections: number;
  history: TrafficPoint[];
  system: SystemInfo;
}

/** A finished (or still active) VPN session from the connection log. */
export interface ConnectionLog {
  username: string;
  commonName: string;
  virtualIp: string | null;
  remoteIp: string | null;
  daemonName: string | null;
  connectedAt: string;
  disconnectedAt: string | null;
  bytesIn: number;
  bytesOut: number;
  durationSeconds: number;
}

/** Server-level settings store: arbitrary JSON values keyed by string. */
export interface ServerSettings {
  [key: string]: unknown;
}

/** Generic paginated response wrapper used by the audit log API. */
export interface PageDto<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** A single admin/auth audit trail entry. */
export interface AuditLogEntry {
  id: string;
  actorId: string | null;
  actorName: string | null;
  action: string;
  category: string;
  targetId: string | null;
  targetType: string | null;
  detail: string | null;
  ip: string | null;
  createdAt: string;
}

/** TOTP provisioning payload: fresh secret plus QR/otpauth for the authenticator app. */
export interface MfaSetup {
  secret: string;
  otpAuthUrl: string;
  qrDataUrl: string;
}

/** Management view of an API token; the raw value is shown only at creation. */
export interface ApiToken {
  id: string;
  label: string;
  prefix: string;
  role: "ADMIN" | "RESELLER";
  expiresAt: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  createdBy: string | null;
}

/** Creation response carrying the one-time plaintext token. */
export interface ApiTokenCreated {
  token: ApiToken;
  rawToken: string;
}

/** Effective brand configuration resolved from settings (served anonymously). */
export interface Brand {
  name: string;
  primaryColor: string;
  footer: string;
  logoUrl: string | null;
}

/** Snapshot of the running configuration for support/auditing. */
export interface ConfigReport {
  brand: string;
  version: string;
  generatedAt: string;
  dbType: string;
  dataDirs: { pki: string; ccd: string; config: string; logs: string };
  serverSettings: Record<string, unknown>;
  daemons: { index: number; name: string; port: number; proto: string; enabled: boolean }[];
  pki: { total: number; valid: number; revoked: number; expired: number; expiringSoon: number };
  users: number;
  groups: number;
}

/** Metadata about a stored backup archive. */
export interface BackupInfo {
  name: string;
  sizeBytes: number;
  createdAt: string;
}

/** Result of a restore; the backend must be restarted when the database was replaced. */
export interface RestoreResult {
  restartRequired: boolean;
  message: string;
}

/** Triggers a browser download for a backend-generated profile file. */
export function downloadOvpn(file: OvpnFile) {
  const blob = new Blob([file.content], { type: "application/x-openvpn-profile" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = file.filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** Downloads a backup archive to disk, honoring the backend's attachment filename. */
export async function downloadBackup(name: string): Promise<void> {
  const res = await fetch(`${API_BASE}${endpoints.backups}/${encodeURIComponent(name)}/download`, {
    headers: { Authorization: `Bearer ${tokenStore.access}` },
  });
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  }
  const blob = await res.blob();
  const disposition = res.headers.get("Content-Disposition") ?? "";
  const match = /filename="?([^";]+)"?/.exec(disposition);
  const filename = match?.[1] ?? name;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
