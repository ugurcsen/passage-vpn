package com.opnl.vpn.api.admin;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin CRUD over the server-level settings store (generic JSON key/value). */
@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Settings", description = "Server-level settings (admin-only)")
public class SettingsAdminController {

  private static final Pattern SETTING_KEY = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");

  private final SettingsService settingsService;
  private final DaemonService daemonService;
  private final AuditLogService auditLogService;

  public SettingsAdminController(
      SettingsService settingsService,
      DaemonService daemonService,
      AuditLogService auditLogService) {
    this.settingsService = settingsService;
    this.daemonService = daemonService;
    this.auditLogService = auditLogService;
  }

  @GetMapping
  public Map<String, Object> list() {
    return settingsService.serverSettings();
  }

  @PutMapping("/{key}")
  public Map<String, Object> put(
      @PathVariable String key, @Valid @RequestBody UpdateSettingRequest request) {
    if (!SETTING_KEY.matcher(key).matches()) {
      throw ApiException.badRequest(
          "invalid_setting_key", "Setting key must be 1-64 chars of [a-zA-Z0-9_.-]");
    }
    validateKnownKey(key, request.value());
    settingsService.setServerSetting(key, request.value());
    // The daemon configs surface network_mode; rewrite them so the firewall updates.
    if (SettingKeys.NETWORK_MODE.equals(key)) {
      daemonService.writeAll();
    }
    auditLogService.record(
        "SETTING_SET", AuditLogService.CAT_SETTING, key, "setting", Map.of("key", key));
    return settingsService.serverSettings();
  }

  @DeleteMapping("/{key}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String key) {
    settingsService.deleteServerSetting(key);
    // Deleting the setting restores the default "nat" mode; rewrite configs accordingly.
    if (SettingKeys.NETWORK_MODE.equals(key)) {
      daemonService.writeAll();
    }
    auditLogService.record(
        "SETTING_DELETE", AuditLogService.CAT_SETTING, key, "setting", Map.of("key", key));
  }

  /** Validates values for well-known keys that need range/format checks. */
  private static void validateKnownKey(String key, Object value) {
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

  /** Update payload: the JSON value to store under the key. */
  public record UpdateSettingRequest(@NotNull Object value) {}
}
