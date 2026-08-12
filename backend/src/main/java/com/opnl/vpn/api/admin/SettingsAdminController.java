package com.opnl.vpn.api.admin;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
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
public class SettingsAdminController {

  private static final Pattern SETTING_KEY = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");

  private final SettingsService settingsService;
  private final DaemonService daemonService;

  public SettingsAdminController(SettingsService settingsService, DaemonService daemonService) {
    this.settingsService = settingsService;
    this.daemonService = daemonService;
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
    if (SettingKeys.NETWORK_MODE.equals(key) && !isValidNetworkMode(request.value())) {
      throw ApiException.badRequest(
          "invalid_network_mode", "network_mode must be \"nat\" or \"routed\"");
    }
    settingsService.setServerSetting(key, request.value());
    // The daemon configs surface network_mode; rewrite them so the firewall updates.
    if (SettingKeys.NETWORK_MODE.equals(key)) {
      daemonService.writeAll();
    }
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
  }

  private static boolean isValidNetworkMode(Object value) {
    return value instanceof String s && (s.equals("nat") || s.equals("routed"));
  }

  /** Update payload: the JSON value to store under the key. */
  public record UpdateSettingRequest(@NotNull Object value) {}
}
