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

  /** Restrict routing to these networks, comma separated. Empty = allow all. */
  public static final String ROUTE_RESTRICTION = "route_restriction";

  /** Comma-separated DNS servers pushed to this account's clients. */
  public static final String DNS_SERVERS = "dns_servers";

  /** DNS domain pushed to clients (OpenVPN `push "dhcp-option DOMAIN ..."`). */
  public static final String DNS_DOMAIN = "dns_domain";

  /** Require TOTP code at VPN connect time even when user auth is enabled. */
  public static final String REQUIRE_MFA_ON_CONNECT = "require_mfa_on_connect";
}
