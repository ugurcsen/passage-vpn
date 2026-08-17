package com.passagevpn.syslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyslogServiceTest {

  private SettingsService settingsService;
  private SyslogService service;

  @BeforeEach
  void setUp() {
    settingsService = mock(SettingsService.class);
    service = new SyslogService(settingsService);
    when(settingsService.serverSettings()).thenReturn(Map.of());
  }

  @Test
  void isEnabledReflectsSetting() {
    assertThat(service.isEnabled()).isFalse();
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.SYSLOG_ENABLED, false));
    assertThat(service.isEnabled()).isFalse();
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.SYSLOG_ENABLED, true));
    assertThat(service.isEnabled()).isTrue();
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.SYSLOG_ENABLED, "true"));
    assertThat(service.isEnabled()).isFalse();
  }

  @Test
  void emitIsNoOpWhenDisabled() {
    assertThatCode(() -> service.emit("auth", "hello")).doesNotThrowAnyException();
    verify(settingsService, times(1)).serverSettings();
  }

  @Test
  void emitIsNoOpForBlankOrNullMessage() {
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.SYSLOG_ENABLED, true));
    assertThatCode(() -> service.emit("auth", "   ")).doesNotThrowAnyException();
    assertThatCode(() -> service.emit("auth", null)).doesNotThrowAnyException();
    verify(settingsService, times(2)).serverSettings();
  }

  @Test
  void emitSendsRfc3164DatagramToConfiguredTarget() throws Exception {
    try (DatagramSocket receiver = new DatagramSocket(0)) {
      when(settingsService.serverSettings())
          .thenReturn(
              Map.of(
                  SettingKeys.SYSLOG_ENABLED,
                  true,
                  SettingKeys.SYSLOG_HOST,
                  "127.0.0.1",
                  SettingKeys.SYSLOG_PORT,
                  receiver.getLocalPort(),
                  SettingKeys.SYSLOG_FACILITY,
                  "auth"));

      service.emit("auth", "login failure for alice");

      DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
      receiver.setSoTimeout(3000);
      receiver.receive(packet);
      String payload = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
      assertThat(payload).startsWith("<38>"); // facility 4 * 8 + severity 6
      assertThat(payload).contains("passage-vpn: login failure for alice");
    }
  }

  @Test
  void emitFallsBackToLocal0ForUnknownFacility() throws Exception {
    try (DatagramSocket receiver = new DatagramSocket(0)) {
      when(settingsService.serverSettings())
          .thenReturn(
              Map.of(
                  SettingKeys.SYSLOG_ENABLED,
                  true,
                  SettingKeys.SYSLOG_HOST,
                  "127.0.0.1",
                  SettingKeys.SYSLOG_PORT,
                  receiver.getLocalPort(),
                  SettingKeys.SYSLOG_FACILITY,
                  "bogus"));

      service.emit("auth", "hello");

      DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
      receiver.setSoTimeout(3000);
      receiver.receive(packet);
      String payload = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
      assertThat(payload).startsWith("<134>"); // local0 = 16 * 8 + severity 6
    }
  }

  @Test
  void emitFallsBackToLocal0ForBlankFacility() throws Exception {
    try (DatagramSocket receiver = new DatagramSocket(0)) {
      when(settingsService.serverSettings())
          .thenReturn(
              Map.of(
                  SettingKeys.SYSLOG_ENABLED,
                  true,
                  SettingKeys.SYSLOG_HOST,
                  "127.0.0.1",
                  SettingKeys.SYSLOG_PORT,
                  receiver.getLocalPort(),
                  SettingKeys.SYSLOG_FACILITY,
                  "   "));

      service.emit("auth", "hello");

      DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
      receiver.setSoTimeout(3000);
      receiver.receive(packet);
      String payload = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
      assertThat(payload).startsWith("<134>");
    }
  }

  @Test
  void emitSwallowsUnresolvableHost() {
    when(settingsService.serverSettings())
        .thenReturn(
            Map.of(
                SettingKeys.SYSLOG_ENABLED, true, SettingKeys.SYSLOG_HOST, "no-such-host.invalid"));

    assertThatCode(() -> service.emit("auth", "hello")).doesNotThrowAnyException();
  }

  @Test
  void emitFallsBackToDefaultPortForOutOfRangePort() {
    when(settingsService.serverSettings())
        .thenReturn(
            Map.of(
                SettingKeys.SYSLOG_ENABLED,
                true,
                SettingKeys.SYSLOG_HOST,
                "127.0.0.1",
                SettingKeys.SYSLOG_PORT,
                99999));

    assertThatCode(() -> service.emit("auth", "hello")).doesNotThrowAnyException();
  }

  @Test
  void formatRfc3164RendersPriorityTimestampHostAndMessage() {
    String formatted = service.formatRfc3164(38, "test message");

    assertThat(formatted).startsWith("<38>");
    assertThat(formatted)
        .matches("<38>[A-Z][a-z]{2} \\d{2} \\d{2}:\\d{2}:\\d{2} .*passage-vpn: test message");
    assertThat(formatted).endsWith("passage-vpn: test message");
  }
}
