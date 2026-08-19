package com.passagevpn.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.passagevpn.api.BrandDto;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the effective-brand resolution. */
class BrandServiceTest {

  private SettingsService settingsService;
  private PassageProperties properties;
  private BrandService brandService;

  @BeforeEach
  void setUp() {
    settingsService = mock(SettingsService.class);
    properties = new PassageProperties("./data", "OpenVPN Panel", "secret", null, null, null, null);
    brandService = new BrandService(settingsService, properties);
  }

  @Test
  void usesConfiguredDefaultsWhenUnset() {
    when(settingsService.serverSettings()).thenReturn(Map.of());

    BrandDto brand = brandService.brand();

    assertThat(brand.name()).isEqualTo("OpenVPN Panel");
    assertThat(brand.primaryColor()).isEqualTo("#4f8cff");
    assertThat(brand.footer()).isEmpty();
    assertThat(brand.logoUrl()).isNull();
  }

  @Test
  void overridesDefaultsFromSettings() {
    when(settingsService.serverSettings())
        .thenReturn(
            Map.of(
                SettingKeys.BRAND_NAME, "Acme VPN",
                SettingKeys.BRAND_PRIMARY_COLOR, "#ff8800",
                SettingKeys.BRAND_FOOTER, "Acme Corp",
                SettingKeys.BRAND_LOGO_URL, "https://example.com/logo.png"));

    BrandDto brand = brandService.brand();

    assertThat(brand.name()).isEqualTo("Acme VPN");
    assertThat(brand.primaryColor()).isEqualTo("#ff8800");
    assertThat(brand.footer()).isEqualTo("Acme Corp");
    assertThat(brand.logoUrl()).isEqualTo("https://example.com/logo.png");
  }

  @Test
  void ignoresBlankBrandNameInFavorOfDefault() {
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.BRAND_NAME, "  "));

    assertThat(brandService.brand().name()).isEqualTo("OpenVPN Panel");
  }
}
