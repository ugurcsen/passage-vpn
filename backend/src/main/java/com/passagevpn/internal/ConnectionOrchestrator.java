package com.passagevpn.internal;

import com.passagevpn.access.AccessRuleService;
import com.passagevpn.access.RuleEngine.IptablesResult;
import com.passagevpn.monitor.ConnectionLogService;
import com.passagevpn.network.ConnectionRegistry;
import com.passagevpn.network.DaemonService;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates VPN connection lifecycle: user lookup, ban/lock check, connection-limit enforcement,
 * registry updates, connection-log recording, and iptables resolution. Extracted from {@link
 * InternalController} to decouple domain logic from HTTP adaptation.
 */
@Slf4j
@Service
public class ConnectionOrchestrator {

  private final UserRepository userRepository;
  private final SettingsService settingsService;
  private final ConnectionRegistry connectionRegistry;
  private final ConnectionLogService connectionLogService;
  private final AccessRuleService ruleService;
  private final DaemonService daemonService;

  public ConnectionOrchestrator(
      UserRepository userRepository,
      SettingsService settingsService,
      ConnectionRegistry connectionRegistry,
      ConnectionLogService connectionLogService,
      AccessRuleService ruleService,
      DaemonService daemonService) {
    this.userRepository = userRepository;
    this.settingsService = settingsService;
    this.connectionRegistry = connectionRegistry;
    this.connectionLogService = connectionLogService;
    this.ruleService = ruleService;
    this.daemonService = daemonService;
  }

  /**
   * Authorizes and registers an incoming VPN connection. Returns allowed with iptables commands on
   * success, or denied with a reason code on failure.
   */
  public ConnectResult connect(
      String commonName,
      String username,
      String virtualIp,
      String virtualIp6,
      String remoteIp,
      String daemonName,
      String nodeId) {
    User user = resolveUser(commonName, username);
    if (user == null) {
      return ConnectResult.deny("unknown_user");
    }
    if (user.isBanned() || user.isLocked(Instant.now())) {
      return ConnectResult.deny(user.isBanned() ? "user_banned" : "user_locked");
    }
    int max = maxConnections(user);
    if (max > 0 && connectionRegistry.countByUsername(user.getUsername()) >= max) {
      return ConnectResult.deny("max_connections");
    }
    connectionRegistry.register(
        user.getUsername(),
        user.getUsername(),
        virtualIp,
        virtualIp6,
        remoteIp,
        daemonName,
        nodeId);
    connectionLogService.sessionStarted(
        user.getUsername(), user.getUsername(), virtualIp, remoteIp, daemonName, nodeId);
    IptablesResult result =
        ruleService.iptablesFor(
            user.getUsername(),
            virtualIp,
            virtualIp6,
            user.getId(),
            daemonService.ipv6Enabled(daemonIndexOf(daemonName)));
    return new ConnectResult(
        true, null, List.of(), result.apply(), result.remove(), result.apply6(), result.remove6());
  }

  /** Resolves the iptables teardown for a disconnecting client. */
  public DisconnectResult disconnect(
      String commonName, String virtualIp, String virtualIp6, String daemonName) {
    User user = userRepository.findByUsername(commonName).orElse(null);
    if (user == null) {
      return new DisconnectResult(List.of(), List.of());
    }
    connectionRegistry.unregister(user.getUsername(), null);
    connectionLogService.sessionEnded(user.getUsername());
    IptablesResult result =
        ruleService.iptablesFor(
            user.getUsername(),
            virtualIp,
            virtualIp6,
            user.getId(),
            daemonService.ipv6Enabled(daemonIndexOf(daemonName)));
    return new DisconnectResult(result.remove(), result.remove6());
  }

  /**
   * Normalizes account-state reasons before they leave the restricted network so a failing connect
   * attempt never leaks whether an account is locked or disabled.
   */
  static String sanitizeReason(String reason) {
    if (reason == null) {
      return null;
    }
    return switch (reason) {
      case "account_locked", "account_disabled" -> "invalid_credentials";
      default -> reason;
    };
  }

  private User resolveUser(String commonName, String username) {
    String lookup = (commonName == null || commonName.isBlank()) ? username : commonName;
    return userRepository.findByUsername(lookup).orElse(null);
  }

  private int maxConnections(User user) {
    Map<String, Object> effective = settingsService.effectiveForUser(user.getId());
    return asInt(effective.get(SettingKeys.MAX_CONNECTIONS));
  }

  static int daemonIndexOf(String daemonName) {
    if (daemonName == null) {
      return 0;
    }
    int idx = daemonName.lastIndexOf('-');
    try {
      return idx >= 0 ? Integer.parseInt(daemonName.substring(idx + 1).trim()) : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static int asInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String s) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  public record ConnectResult(
      boolean allowed,
      String reason,
      List<String> pushes,
      List<String> iptablesApply,
      List<String> iptablesRemove,
      List<String> iptablesApply6,
      List<String> iptablesRemove6) {

    static ConnectResult deny(String reason) {
      return new ConnectResult(
          false, reason, List.of(), List.of(), List.of(), List.of(), List.of());
    }
  }

  public record DisconnectResult(List<String> remove, List<String> remove6) {}
}
