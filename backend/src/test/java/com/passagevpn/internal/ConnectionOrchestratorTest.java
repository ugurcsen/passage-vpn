package com.passagevpn.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.access.AccessRuleService;
import com.passagevpn.access.RuleEngine.IptablesResult;
import com.passagevpn.internal.ConnectionOrchestrator.ConnectResult;
import com.passagevpn.internal.ConnectionOrchestrator.DisconnectResult;
import com.passagevpn.monitor.ConnectionLogService;
import com.passagevpn.network.ConnectionRegistry;
import com.passagevpn.network.DaemonService;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionOrchestratorTest {

  private UserRepository userRepository;
  private SettingsService settingsService;
  private ConnectionRegistry connectionRegistry;
  private ConnectionLogService connectionLogService;
  private AccessRuleService ruleService;
  private DaemonService daemonService;
  private ConnectionOrchestrator orchestrator;

  private User user(boolean banned, boolean locked) {
    return User.builder()
        .id("u1")
        .username("alice")
        .role(User.Role.USER)
        .banned(banned)
        .lockedUntil(locked ? Instant.now().plusSeconds(600) : null)
        .createdAt(Instant.now())
        .build();
  }

  private IptablesResult iptables() {
    return new IptablesResult(
        List.of("iptables -N PASSAGE_x"), List.of("iptables -X PASSAGE_x"), List.of(), List.of());
  }

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    settingsService = mock(SettingsService.class);
    connectionRegistry = new ConnectionRegistry();
    connectionLogService = mock(ConnectionLogService.class);
    ruleService = mock(AccessRuleService.class);
    daemonService = mock(DaemonService.class);
    orchestrator =
        new ConnectionOrchestrator(
            userRepository,
            settingsService,
            connectionRegistry,
            connectionLogService,
            ruleService,
            daemonService);
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(false, false)));
    when(settingsService.effectiveForUser("u1")).thenReturn(Map.of());
    when(daemonService.ipv6Enabled(anyInt())).thenReturn(false);
    when(ruleService.iptablesFor(anyString(), anyString(), any(), anyString(), anyBoolean()))
        .thenReturn(iptables());
  }

  @Test
  void connectDeniesUnknownUser() {
    ConnectResult result = orchestrator.connect("ghost", null, "10.8.0.9", null, null, null, null);
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("unknown_user");
  }

  @Test
  void connectDeniesBannedUser() {
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(true, false)));
    ConnectResult result = orchestrator.connect("alice", null, "10.8.0.9", null, null, null, null);
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("user_banned");
  }

  @Test
  void connectDeniesLockedUser() {
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(false, true)));
    ConnectResult result = orchestrator.connect("alice", null, "10.8.0.9", null, null, null, null);
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("user_locked");
  }

  @Test
  void connectDeniesWhenMaxConnectionsReached() {
    when(settingsService.effectiveForUser("u1")).thenReturn(Map.of("max_connections", 1));
    connectionRegistry.register("alice", "alice", "10.8.0.8", null, "9.9.9.9", "daemon-0");
    ConnectResult result =
        orchestrator.connect("alice", null, "10.8.0.9", null, "1.2.3.4", "daemon-0", null);
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("max_connections");
  }

  @Test
  void connectAllowsUnderMaxConnectionsLimit() {
    when(settingsService.effectiveForUser("u1")).thenReturn(Map.of("max_connections", 2));
    connectionRegistry.register("alice", "alice", "10.8.0.8", null, "9.9.9.9", "daemon-0");
    ConnectResult result =
        orchestrator.connect("alice", null, "10.8.0.9", null, "1.2.3.4", "daemon-0", null);
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void connectRegistersSessionAndReturnsIptables() {
    ConnectResult result =
        orchestrator.connect("alice", null, "10.8.0.9", null, "1.2.3.4", "daemon-0", null);
    assertThat(result.allowed()).isTrue();
    assertThat(result.iptablesApply()).containsExactly("iptables -N PASSAGE_x");
    assertThat(result.iptablesRemove()).containsExactly("iptables -X PASSAGE_x");
    assertThat(connectionRegistry.byVirtualIp("10.8.0.9"))
        .hasValueSatisfying(
            s -> {
              assertThat(s.username()).isEqualTo("alice");
              assertThat(s.remoteIp()).isEqualTo("1.2.3.4");
              assertThat(s.daemonName()).isEqualTo("daemon-0");
            });
    verify(connectionLogService)
        .sessionStarted("alice", "alice", "10.8.0.9", "1.2.3.4", "daemon-0", null);
  }

  @Test
  void connectFallsBackToUsernameWhenCommonNameBlank() {
    ConnectResult result = orchestrator.connect("", "alice", "10.8.0.9", null, null, null, null);
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void disconnectUnregistersSessionAndReturnsTeardown() {
    connectionRegistry.register("alice", "alice", "10.8.0.9", null, "1.2.3.4", "daemon-0");
    DisconnectResult result = orchestrator.disconnect("alice", "10.8.0.9", null, "daemon-0");
    assertThat(result.remove()).containsExactly("iptables -X PASSAGE_x");
    assertThat(connectionRegistry.sessions()).isEmpty();
    verify(connectionLogService).sessionEnded("alice");
  }

  @Test
  void disconnectReturnsEmptyForUnknownUser() {
    when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
    DisconnectResult result = orchestrator.disconnect("ghost", "10.8.0.9", null, "daemon-0");
    assertThat(result.remove()).isEmpty();
    assertThat(result.remove6()).isEmpty();
  }

  @Test
  void sanitizeReasonMasksAccountLocked() {
    assertThat(ConnectionOrchestrator.sanitizeReason("account_locked"))
        .isEqualTo("invalid_credentials");
  }

  @Test
  void sanitizeReasonMasksAccountDisabled() {
    assertThat(ConnectionOrchestrator.sanitizeReason("account_disabled"))
        .isEqualTo("invalid_credentials");
  }

  @Test
  void sanitizeReasonPassesThroughOtherReasons() {
    assertThat(ConnectionOrchestrator.sanitizeReason("user_banned")).isEqualTo("user_banned");
  }

  @Test
  void sanitizeReasonReturnsNullForNull() {
    assertThat(ConnectionOrchestrator.sanitizeReason(null)).isNull();
  }

  @Test
  void daemonIndexOfParsesTrailingNumber() {
    assertThat(ConnectionOrchestrator.daemonIndexOf("daemon-0")).isEqualTo(0);
    assertThat(ConnectionOrchestrator.daemonIndexOf("daemon-3")).isEqualTo(3);
  }

  @Test
  void daemonIndexOfReturnsZeroForNull() {
    assertThat(ConnectionOrchestrator.daemonIndexOf(null)).isEqualTo(0);
  }

  @Test
  void daemonIndexOfReturnsZeroForNoDash() {
    assertThat(ConnectionOrchestrator.daemonIndexOf("daemon")).isEqualTo(0);
  }
}
