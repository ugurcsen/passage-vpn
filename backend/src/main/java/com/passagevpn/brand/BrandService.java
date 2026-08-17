package com.passagevpn.brand;

import com.passagevpn.api.BrandDto;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective brand (name, primary color, footer, logo) from settings, falling back to
 * the configured defaults when a brand setting is unset. Exposed anonymously so the login page and
 * theme can be branded before authentication.
 */
@Service
public class BrandService {

  public static final String DEFAULT_PRIMARY_COLOR = "#4f8cff";

  private final SettingsService settingsService;
  private final PassageProperties properties;

  public BrandService(SettingsService settingsService, PassageProperties properties) {
    this.settingsService = settingsService;
    this.properties = properties;
  }

  public BrandDto brand() {
    Map<String, Object> s = settingsService.serverSettings();
    return new BrandDto(
        stringValue(s, SettingKeys.BRAND_NAME, properties.brandName()),
        stringValue(s, SettingKeys.BRAND_PRIMARY_COLOR, DEFAULT_PRIMARY_COLOR),
        stringValue(s, SettingKeys.BRAND_FOOTER, ""),
        stringValue(s, SettingKeys.BRAND_LOGO_URL, null));
  }

  private static String stringValue(Map<String, Object> s, String key, String fallback) {
    Object v = s.get(key);
    return (v instanceof String str && !str.isBlank()) ? str : fallback;
  }
}
