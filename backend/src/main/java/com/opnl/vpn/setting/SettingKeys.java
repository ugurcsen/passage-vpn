package com.opnl.vpn.setting;

/** Well-known setting keys shared by server, group and user levels. */
public final class SettingKeys {

  private SettingKeys() {}

  /** True when connections for this account are denied entirely. */
  public static final String ACCOUNT_DISABLED = "account_disabled";

  /** True when the account must log in and set a new password next time. */
  public static final String MUST_CHANGE_PASSWORD = "must_change_password";

  /** Default group for new users when none is assigned. */
  public static final String DEFAULT_GROUP = "default_group";

  /** Maximum concurrent VPN connections for the account (0 = unlimited). */
  public static final String MAX_CONNECTIONS = "max_connections";

  /** Static VPN IP for the account (CCD `ifconfig-push`). */
  public static final String STATIC_IP = "static_ip";

  /** Group-level static IP pool, e.g. "10.8.0.100-10.8.0.199". */
  public static final String STATIC_IP_POOL = "static_ip_pool";

  /** Static VPN IPv6 for the account (CCD `ifconfig-ipv6-push`). */
  public static final String STATIC_IPV6 = "static_ipv6";

  /** Group-level static IPv6 pool, e.g. "fd00:1::10-fd00:1::ff". */
  public static final String STATIC_IPV6_POOL = "static_ipv6_pool";

  /** Restrict routing to these networks, comma separated. Empty = allow all. */
  public static final String ROUTE_RESTRICTION = "route_restriction";

  /** How the VPN server forwards client traffic: "nat" (masquerade) or "routed" (no NAT). */
  public static final String NETWORK_MODE = "network_mode";

  /** Tunnel mode for the account: "full" (redirect all traffic) or "split" (routes only). */
  public static final String TUNNEL_MODE = "tunnel_mode";

  /** Comma-separated DNS servers pushed to this account's clients. */
  public static final String DNS_SERVERS = "dns_servers";

  /** DNS domain pushed to clients (OpenVPN `push "dhcp-option DOMAIN ..."`). */
  public static final String DNS_DOMAIN = "dns_domain";

  /** Require TOTP code at VPN connect time even when user auth is enabled. */
  public static final String REQUIRE_MFA_ON_CONNECT = "require_mfa_on_connect";

  /** True when the account must use TOTP MFA: forced enrollment and second factor at login. */
  public static final String REQUIRE_MFA = "require_mfa";

  /** Days of closed VPN session history kept before the periodic purge. */
  public static final String CONNECTION_LOGS_RETENTION_DAYS = "connection_logs_retention_days";

  /** Days of audit log entries kept before the periodic purge. */
  public static final String AUDIT_LOGS_RETENTION_DAYS = "audit_logs_retention_days";

  /** True when audit/auth events are shipped to a syslog server over UDP (RFC3164). */
  public static final String SYSLOG_ENABLED = "syslog_enabled";

  /** Syslog server host (IP or name). */
  public static final String SYSLOG_HOST = "syslog_host";

  /** Syslog server UDP port. */
  public static final String SYSLOG_PORT = "syslog_port";

  /** Syslog facility used in the RFC3164 header, e.g. "local0" or "user". */
  public static final String SYSLOG_FACILITY = "syslog_facility";

  /** Brand/product name shown in the UI (login, sidebar, footer). */
  public static final String BRAND_NAME = "brand_name";

  /** Brand primary color as a hex value, e.g. "#4f8cff". Applied to the UI theme. */
  public static final String BRAND_PRIMARY_COLOR = "brand_primary_color";

  /** Optional footer text shown on the login page. */
  public static final String BRAND_FOOTER = "brand_footer";

  /** Optional brand logo URL shown on the login page and sidebar. */
  public static final String BRAND_LOGO_URL = "brand_logo_url";

  /**
   * Optional post-auth hook script executed by the backend after a successful VPN connect. A bare
   * filename is resolved inside the shared scripts directory; an absolute path is used as-is.
   */
  public static final String POST_AUTH_SCRIPT = "post_auth_script";

  /** Timeout in seconds for the post-auth hook script (default 10). */
  public static final String POST_AUTH_TIMEOUT_SECONDS = "post_auth_timeout_seconds";

  /**
   * Profile types that may be downloaded from the client portal, e.g. {@code ["USER_LOCKED",
   * "SERVER_LOCKED"]}. When unset, the portal serves only the password-bound certificate types
   * (USER_LOCKED, SERVER_LOCKED); AUTO_LOGIN and GENERIC must be explicitly enabled by an admin.
   */
  public static final String PORTAL_PROFILE_TYPES = "portal_profile_types";

  /**
   * True when generated .ovpn profiles embed every daemon serving the profile type as a {@code
   * remote} endpoint (with {@code remote-random}) instead of only the first one. Defaults to on so
   * multi-node deployments load-balance automatically; set to false to always pin profiles to a
   * single endpoint.
   */
  public static final String PROFILE_MULTI_REMOTE = "profile_multi_remote";

  /**
   * Certificate rotation policy for certificates nearing expiry: {@code "off"} (never rotate
   * automatically), {@code "notify"} (warn only, the default) or {@code "auto"} (rotate
   * automatically; the user's downloaded profiles become invalid and must be re-downloaded).
   */
  public static final String CERT_AUTO_ROTATE = "cert_auto_rotate";

  /** Days before expiry at which the auto-rotate policy applies (default 14). */
  public static final String CERT_ROTATE_DAYS_BEFORE = "cert_rotate_days_before";
}
