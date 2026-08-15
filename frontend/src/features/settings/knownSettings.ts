/** Editor type used for a known setting value. */
export type SettingValueType =
  | "string"
  | "number"
  | "boolean"
  | "list"
  | "serverConfig"
  | "json"
  | "choice";

/** Metadata describing a well-known server setting so it can be edited with a friendly control. */
export interface KnownSetting {
  /** Setting key stored on the server (e.g. "max_connections"). */
  key: string;
  /** Human-readable label. */
  label: string;
  /** Short description shown under the label. */
  description: string;
  /** Determines which editor control is rendered. */
  type: SettingValueType;
  /** Placeholder for text-based editors. */
  placeholder?: string;
  /** Allowed values for `choice`-typed settings (rendered as a select). */
  options?: string[];
}

/**
 * Well-known settings (mirrors `SettingKeys` in the backend). Set at the server level they act as
 * defaults for every account; group and per-user settings override them.
 */
export const KNOWN_SETTINGS: KnownSetting[] = [
  {
    key: "network",
    label: "VPN server network",
    description: "Port, protocol and addressing for the primary daemon (bootstrap default config).",
    type: "serverConfig",
  },
  {
    key: "default_group",
    label: "Default group",
    description: "Group assigned to new accounts when none is chosen.",
    type: "string",
    placeholder: "e.g. employees",
  },
  {
    key: "max_connections",
    label: "Max connections",
    description: "Maximum concurrent VPN connections per account. 0 means unlimited.",
    type: "number",
    placeholder: "0",
  },
  {
    key: "static_ip",
    label: "Static IP",
    description: "Static VPN IPv4 address for the account (pushed via CCD ifconfig-push).",
    type: "string",
    placeholder: "e.g. 10.8.0.42",
  },
  {
    key: "static_ipv6",
    label: "Static IPv6",
    description: "Static VPN IPv6 address for the account (pushed via CCD ifconfig-ipv6-push).",
    type: "string",
    placeholder: "e.g. fd00:1::42",
  },
  {
    key: "static_ipv6_pool",
    label: "Static IPv6 pool",
    description: "IPv6 address range (start-end) allocated to group members; the group is then reachable as a rule destination.",
    type: "string",
    placeholder: "e.g. fd00:1::10-fd00:1::ff",
  },
  {
    key: "dns_servers",
    label: "DNS servers",
    description: "DNS servers pushed to clients, comma separated.",
    type: "list",
    placeholder: "e.g. 1.1.1.1, 8.8.8.8",
  },
  {
    key: "dns_domain",
    label: "DNS domain",
    description: "DNS search domain pushed to clients.",
    type: "string",
    placeholder: "e.g. vpn.example.com",
  },
  {
    key: "route_restriction",
    label: "Route restriction",
    description: "Restrict routing to these networks (CIDR), comma separated. Empty allows all.",
    type: "list",
    placeholder: "e.g. 10.0.0.0/8, 192.168.0.0/16",
  },
  {
    key: "require_mfa_on_connect",
    label: "Require MFA on connect",
    description: "Require a TOTP code at VPN connect time in addition to the password.",
    type: "boolean",
  },
  {
    key: "require_mfa",
    label: "Require MFA",
    description:
      "Force all users to set up two-factor authentication and to enter a TOTP code at login and VPN connect. Users without MFA are prompted to enroll at their next login.",
    type: "boolean",
  },
  {
    key: "tunnel_mode",
    label: "Tunnel mode",
    description: "Full tunnel routes all traffic through the VPN; split tunnel routes only the configured networks.",
    type: "string",
    placeholder: "full or split",
  },
  {
    key: "account_disabled",
    label: "Account disabled",
    description: "Deny VPN connections entirely for the account.",
    type: "boolean",
  },
  {
    key: "must_change_password",
    label: "Must change password",
    description: "Force the account to set a new password at its next login.",
    type: "boolean",
  },
  {
    key: "network_mode",
    label: "Network mode",
    description: "nat masks client traffic behind the server; routed keeps client source IPs and relies on return routes.",
    type: "choice",
    options: ["nat", "routed"],
  },
  {
    key: "brand_name",
    label: "Brand name",
    description: "Product name shown in the login page, title bar and theming.",
    type: "string",
    placeholder: "e.g. Acme VPN",
  },
  {
    key: "brand_primary_color",
    label: "Brand primary color",
    description: "Accent color (hex) used for buttons and highlights.",
    type: "string",
    placeholder: "#4f8cff",
  },
  {
    key: "brand_footer",
    label: "Brand footer",
    description: "Footer text shown on the login page.",
    type: "string",
    placeholder: "e.g. Support: help@acme.com",
  },
  {
    key: "brand_logo_url",
    label: "Brand logo URL",
    description: "Optional logo URL shown instead of the default icon.",
    type: "string",
    placeholder: "https://…/logo.png",
  },
  {
    key: "post_auth_script",
    label: "Post-auth hook script",
    description: "Script run in the backend after a successful VPN login. A bare filename is looked up in the scripts directory (e.g. post-auth-hook.py); an absolute path is used as-is. Failure never drops the connection.",
    type: "string",
    placeholder: "e.g. post-auth-hook.py",
  },
  {
    key: "post_auth_timeout_seconds",
    label: "Post-auth hook timeout",
    description: "Timeout in seconds for the post-auth hook script (1–120, default 10).",
    type: "number",
    placeholder: "10",
  },
  {
    key: "portal_profile_types",
    label: "Portal profile types",
    description:
      "Profile types that may be downloaded from the client portal. When unset only User-locked and Server-locked are available; add AUTO_LOGIN and GENERIC to enable them.",
    type: "list",
    placeholder: "USER_LOCKED, SERVER_LOCKED, AUTO_LOGIN, GENERIC",
  },
  {
    key: "cert_auto_rotate",
    label: "Certificate auto-rotation",
    description:
      "How certificates nearing expiry are handled. notify warns the admin; auto revokes and reissues them automatically, forcing users to download new profiles.",
    type: "choice",
    options: ["off", "notify", "auto"],
  },
  {
    key: "cert_rotate_days_before",
    label: "Rotation lead time",
    description: "Days before expiry at which the auto-rotation policy applies (1–3650, default 14).",
    type: "number",
    placeholder: "14",
  },
  {
    key: "profile_multi_remote",
    label: "Multi-remote profiles",
    description:
      "Embed every daemon serving a profile type as a remote endpoint (with remote-random) in generated .ovpn files so clients load-balance across nodes. Turn off to pin profiles to a single endpoint.",
    type: "boolean",
  },
];

/** Looks up metadata for a setting key, falling back to a raw JSON editor for unknown keys. */
export function knownSetting(key: string): KnownSetting | undefined {
  return KNOWN_SETTINGS.find((s) => s.key === key);
}

/** Editable form state for the `network` (ServerConfig) setting. */
export interface ServerConfigForm {
  port: string;
  proto: "udp" | "tcp" | "udp6" | "tcp6";
  subnet: string;
  subnetMask: string;
  dnsServers: string;
  domain: string;
  extraRoutes: string;
  fullTunnel: boolean;
  clientCertNotRequired: boolean;
  authUserPass: boolean;
  adminHost: string;
  ipv6Enabled: boolean;
  ipv6Subnet: string;
}

/** Defaults matching the backend `ServerConfig.defaults()` (daemon 0, UDP 1194, 10.8.0.0/24). */
export function emptyServerConfigForm(): ServerConfigForm {
  return {
    port: "1194",
    proto: "udp",
    subnet: "10.8.0.0",
    subnetMask: "255.255.255.0",
    dnsServers: "1.1.1.1, 8.8.8.8",
    domain: "",
    extraRoutes: "",
    fullTunnel: true,
    clientCertNotRequired: false,
    authUserPass: true,
    adminHost: "vpn.example.com",
    ipv6Enabled: false,
    ipv6Subnet: "fd00:1::/64",
  };
}

/** Maps a stored `network` value into the editable form, tolerating missing/unknown fields. */
export function serverConfigToForm(value: unknown): ServerConfigForm {
  const cfg = (value ?? {}) as Record<string, unknown>;
  const str = (v: unknown, fallback = "") => (typeof v === "string" ? v : fallback);
  const list = (v: unknown): string =>
    Array.isArray(v) ? v.map(String).join(", ") : typeof v === "string" ? v : "";
  return {
    port: typeof cfg.port === "number" ? String(cfg.port) : "1194",
    proto:
      cfg.proto === "tcp"
        ? "tcp"
        : cfg.proto === "udp6"
          ? "udp6"
          : cfg.proto === "tcp6"
            ? "tcp6"
            : "udp",
    subnet: str(cfg.subnet, "10.8.0.0"),
    subnetMask: str(cfg.subnetMask, "255.255.255.0"),
    dnsServers: list(cfg.dnsServers),
    domain: str(cfg.domain),
    extraRoutes: list(cfg.extraRoutes),
    fullTunnel: cfg.fullTunnel === true,
    clientCertNotRequired: cfg.clientCertNotRequired === true,
    authUserPass: cfg.authUserPass !== false,
    adminHost: str(cfg.adminHost, "vpn.example.com"),
    ipv6Enabled: cfg.ipv6Enabled === true,
    ipv6Subnet: str(cfg.ipv6Subnet, "fd00:1::/64"),
  };
}

/** Maps the editable form back into the wire shape of the backend `ServerConfig` record. */
export function formToServerConfig(form: ServerConfigForm): Record<string, unknown> {
  const split = (s: string) =>
    s
      .split(",")
      .map((item) => item.trim())
      .filter((item) => item.length > 0);
  return {
    daemonIndex: 0,
    port: Number(form.port),
    proto: form.proto,
    subnet: form.subnet.trim(),
    subnetMask: form.subnetMask.trim(),
    dnsServers: split(form.dnsServers),
    domain: form.domain.trim() || null,
    extraRoutes: split(form.extraRoutes),
    fullTunnel: form.fullTunnel,
    clientCertNotRequired: form.clientCertNotRequired,
    authUserPass: form.authUserPass,
    adminHost: form.adminHost.trim(),
    ipv6Enabled: form.ipv6Enabled,
    ipv6Subnet: form.ipv6Enabled ? form.ipv6Subnet.trim() || null : null,
  };
}

/** One-line summary of the network config used in the read-only row display. */
export function serverConfigSummary(value: unknown): string {
  const form = serverConfigToForm(value);
  const proto = form.proto.toUpperCase();
  const dns = form.dnsServers.trim() ? form.dnsServers : "default";
  return `${proto} ${form.port} · ${form.subnet}/${form.subnetMask} · DNS ${dns}`;
}

/**
 * Serializes a value for storage via the generic settings API. Lists are joined into the
 * comma-separated string the backend expects; strings are sent as-is.
 */
export function serializeSetting(type: SettingValueType, value: string): unknown {
  switch (type) {
    case "number": {
      const trimmed = value.trim();
      return trimmed === "" ? 0 : Number(trimmed);
    }
    case "boolean":
      return value === "true";
    case "list":
      return value
        .split(",")
        .map((item) => item.trim())
        .filter((item) => item.length > 0)
        .join(", ");
    case "json":
    case "serverConfig":
      try {
        return JSON.parse(value);
      } catch {
        return value;
      }
    default:
      return value;
  }
}

/**
 * Normalizes a stored value into the editable string form used by the typed editors. Arrays are
 * joined with ", " for list settings so they round-trip through the comma-separated wire format.
 */
export function normalizeSetting(type: SettingValueType, value: unknown): string {
  if (value === null || value === undefined) return "";
  switch (type) {
    case "boolean":
      return value === true || value === "true" ? "true" : "false";
    case "number":
      return typeof value === "number" ? String(value) : String(value);
    case "list":
      return Array.isArray(value) ? value.join(", ") : String(value);
    case "json":
      return typeof value === "string" ? value : JSON.stringify(value, null, 2);
    case "serverConfig":
      return typeof value === "string" ? value : JSON.stringify(value, null, 2);
    default:
      return String(value);
  }
}

/** Formats a stored value for the read-only row display. */
export function displaySetting(type: SettingValueType, value: unknown): string {
  if (value === null || value === undefined) return "—";
  switch (type) {
    case "boolean":
      return value === true || value === "true" ? "On" : "Off";
    case "serverConfig":
      return serverConfigSummary(value);
    case "json":
      return JSON.stringify(value);
    default:
      return String(value);
  }
}
