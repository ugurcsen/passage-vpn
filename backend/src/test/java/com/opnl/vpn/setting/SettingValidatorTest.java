package com.opnl.vpn.setting;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opnl.vpn.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingValidatorTest {

  @Test
  void acceptsValidNetworkModes() {
    assertThatCode(() -> SettingValidator.validate(SettingKeys.NETWORK_MODE, "nat"))
        .doesNotThrowAnyException();
    assertThatCode(() -> SettingValidator.validate(SettingKeys.NETWORK_MODE, "routed"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsInvalidNetworkModes() {
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.NETWORK_MODE, "bridge"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("network_mode must be \"nat\" or \"routed\"");
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.NETWORK_MODE, 42))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void acceptsRetentionDaysWithinRange() {
    assertThatCode(() -> SettingValidator.validate(SettingKeys.AUDIT_LOGS_RETENTION_DAYS, 1))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> SettingValidator.validate(SettingKeys.CONNECTION_LOGS_RETENTION_DAYS, 3650))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsOutOfRangeRetentionDays() {
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.AUDIT_LOGS_RETENTION_DAYS, 0))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.AUDIT_LOGS_RETENTION_DAYS, 3651))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> SettingValidator.validate(SettingKeys.CONNECTION_LOGS_RETENTION_DAYS, -1))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.AUDIT_LOGS_RETENTION_DAYS, "30"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void acceptsSyslogPortsWithinRange() {
    assertThatCode(() -> SettingValidator.validate(SettingKeys.SYSLOG_PORT, 1))
        .doesNotThrowAnyException();
    assertThatCode(() -> SettingValidator.validate(SettingKeys.SYSLOG_PORT, 65535))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsOutOfRangeSyslogPorts() {
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.SYSLOG_PORT, 0))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.SYSLOG_PORT, 65536))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.SYSLOG_PORT, "514"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void acceptsKnownSyslogFacilities() {
    assertThatCode(() -> SettingValidator.validate(SettingKeys.SYSLOG_FACILITY, "user"))
        .doesNotThrowAnyException();
    assertThatCode(() -> SettingValidator.validate(SettingKeys.SYSLOG_FACILITY, "local7"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsUnknownSyslogFacilities() {
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.SYSLOG_FACILITY, "local8"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.SYSLOG_FACILITY, "daemon"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.SYSLOG_FACILITY, 5))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void acceptsValidBrandColors() {
    assertThatCode(() -> SettingValidator.validate(SettingKeys.BRAND_PRIMARY_COLOR, "#4f8cff"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsInvalidBrandColors() {
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.BRAND_PRIMARY_COLOR, "#fff"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.BRAND_PRIMARY_COLOR, "red"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.BRAND_PRIMARY_COLOR, "#GGGGGG"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void acceptsKnownPortalProfileTypes() {
    assertThatCode(
            () ->
                SettingValidator.validate(
                    SettingKeys.PORTAL_PROFILE_TYPES, List.of("USER_LOCKED", "AUTO_LOGIN")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                SettingValidator.validate(
                    SettingKeys.PORTAL_PROFILE_TYPES, "SERVER_LOCKED,GENERIC"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsUnknownPortalProfileTypes() {
    assertThatThrownBy(
            () ->
                SettingValidator.validate(
                    SettingKeys.PORTAL_PROFILE_TYPES, List.of("USER_LOCKED", "BOGUS")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SettingValidator.validate(SettingKeys.PORTAL_PROFILE_TYPES, "NOPE"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void unknownKeysAreNotValidated() {
    assertThatCode(() -> SettingValidator.validate("unknown_key", "anything"))
        .doesNotThrowAnyException();
  }
}
