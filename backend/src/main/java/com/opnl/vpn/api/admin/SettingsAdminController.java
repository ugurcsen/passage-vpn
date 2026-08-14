package com.opnl.vpn.api.admin;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingValidator;
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
    SettingValidator.validate(key, value);
  }

  /** Update payload: the JSON value to store under the key. */
  public record UpdateSettingRequest(@NotNull Object value) {}
}
