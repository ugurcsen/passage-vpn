package com.opnl.vpn.syslog;

import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Ships audit and auth events to a syslog server over UDP using the RFC 3164 message format ({@code
 * <PRI>MMM dd HH:mm:ss HOST TAG: msg}). Target and facility are read from the server settings store
 * at emit time so the Settings page remains the single configuration surface.
 *
 * <p>Emission is strictly best-effort: a disabled target, unreachable host or malformed setting
 * must never break the calling flow, so {@link #emit} never throws.
 */
@Slf4j
@Service
public class SyslogService {

  /** Informational severity (RFC 3164). */
  private static final int SEVERITY_INFO = 6;

  private static final String DEFAULT_HOST = "127.0.0.1";
  private static final int DEFAULT_PORT = 514;
  private static final String DEFAULT_FACILITY = "local0";

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

  private static final Map<String, Integer> FACILITY_NUMBERS =
      Map.ofEntries(
          Map.entry("user", 1),
          Map.entry("daemon", 3),
          Map.entry("auth", 4),
          Map.entry("authpriv", 10),
          Map.entry("local0", 16),
          Map.entry("local1", 17),
          Map.entry("local2", 18),
          Map.entry("local3", 19),
          Map.entry("local4", 20),
          Map.entry("local5", 21),
          Map.entry("local6", 22),
          Map.entry("local7", 23));

  private final SettingsService settingsService;

  public SyslogService(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  /** Whether syslog forwarding is enabled via the {@code syslog_enabled} setting. */
  public boolean isEnabled() {
    return Boolean.TRUE.equals(settingsService.serverSettings().get(SettingKeys.SYSLOG_ENABLED));
  }

  /**
   * Emits a message with the given facility hint (e.g. {@code auth} for authentication events).
   * No-op when forwarding is disabled; failures are logged and swallowed.
   */
  public void emit(String facilityHint, String message) {
    if (!isEnabled() || message == null || message.isBlank()) {
      return;
    }
    try {
      Map<String, Object> settings = settingsService.serverSettings();
      String host = stringSetting(settings, SettingKeys.SYSLOG_HOST, DEFAULT_HOST);
      int port = intSetting(settings, SettingKeys.SYSLOG_PORT, DEFAULT_PORT);
      int facility =
          FACILITY_NUMBERS.getOrDefault(
              stringSetting(settings, SettingKeys.SYSLOG_FACILITY, DEFAULT_FACILITY), 16);
      int pri = facility * 8 + SEVERITY_INFO;
      byte[] payload = formatRfc3164(pri, message).getBytes(StandardCharsets.UTF_8);
      try (DatagramSocket socket = new DatagramSocket()) {
        socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName(host), port));
      }
    } catch (Exception e) {
      log.warn("Cannot send syslog message: {}", e.getMessage());
    }
  }

  /** Renders a single RFC 3164 syslog line with an informational priority. */
  String formatRfc3164(int pri, String message) {
    String host = hostname();
    return "<" + pri + ">" + TIMESTAMP.format(Instant.now()) + " " + host + " opnl-vpn: " + message;
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "localhost";
    }
  }

  private static String stringSetting(Map<String, Object> settings, String key, String fallback) {
    Object value = settings.get(key);
    return value instanceof String s && !s.isBlank() ? s : fallback;
  }

  private static int intSetting(Map<String, Object> settings, String key, int fallback) {
    Object value = settings.get(key);
    if (value instanceof Number n) {
      int v = n.intValue();
      if (v >= 1 && v <= 65535) {
        return v;
      }
    }
    return fallback;
  }
}
