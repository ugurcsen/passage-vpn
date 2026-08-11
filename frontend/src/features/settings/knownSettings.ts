/** Editor type used for a known setting value. */
export type SettingValueType = "string" | "number" | "boolean" | "list" | "json";

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
}

/**
 * Well-known settings (mirrors `SettingKeys` in the backend). Set at the server level they act as
 * defaults for every account; group and per-user settings override them.
 */
export const KNOWN_SETTINGS: KnownSetting[] = [
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
];

/** Looks up metadata for a setting key, falling back to a raw JSON editor for unknown keys. */
export function knownSetting(key: string): KnownSetting | undefined {
  return KNOWN_SETTINGS.find((s) => s.key === key);
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
    case "json":
      return JSON.stringify(value);
    default:
      return String(value);
  }
}
