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
}
