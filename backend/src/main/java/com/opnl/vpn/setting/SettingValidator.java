package com.opnl.vpn.setting;

import com.opnl.vpn.common.ApiException;

/**
 * Shared validation for well-known server settings; used by the settings API and the preflight
 * check.
 */
public final class SettingValidator {

  private SettingValidator() {}

  /** Validates values for well-known keys that need range/format checks. */
  public static void validate(String key, Object value) {
    if (SettingKeys.NETWORK_MODE.equals(key)) {
      if (!(value instanceof String s) || (!s.equals("nat") && !s.equals("routed"))) {
        throw ApiException.badRequest(
            "invalid_network_mode", "network_mode must be \"nat\" or \"routed\"");
      }
      return;
    }
    if (SettingKeys.CONNECTION_LOGS_RETENTION_DAYS.equals(key)
        || SettingKeys.AUDIT_LOGS_RETENTION_DAYS.equals(key)) {
      if (!isIntInRange(value, 1, 3650)) {
        throw ApiException.badRequest(
            "invalid_retention_days", "Retention days must be an integer between 1 and 3650");
      }
      return;
    }
    if (SettingKeys.SYSLOG_PORT.equals(key)) {
      if (!isIntInRange(value, 1, 65535)) {
        throw ApiException.badRequest(
            "invalid_syslog_port", "syslog_port must be an integer between 1 and 65535");
      }
      return;
    }
    if (SettingKeys.SYSLOG_FACILITY.equals(key)
        && (!(value instanceof String s) || (!s.equals("user") && !s.matches("local[0-7]")))) {
      throw ApiException.badRequest(
          "invalid_syslog_facility", "syslog_facility must be user or local0..local7");
    }
    if (SettingKeys.BRAND_PRIMARY_COLOR.equals(key)
        && (!(value instanceof String s) || !s.matches("#[0-9a-fA-F]{6}"))) {
      throw ApiException.badRequest(
          "invalid_brand_color", "brand_primary_color must be a hex color like #4f8cff");
    }
  }

  private static boolean isIntInRange(Object value, int min, int max) {
    return value instanceof Number n && n.longValue() >= min && n.longValue() <= max;
  }
}
