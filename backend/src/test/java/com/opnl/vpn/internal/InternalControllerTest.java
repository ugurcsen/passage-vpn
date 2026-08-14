package com.opnl.vpn.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.access.AccessRuleService;
import com.opnl.vpn.access.RuleEngine.IptablesResult;
import com.opnl.vpn.auth.AuthService;
import com.opnl.vpn.auth.AuthService.VpnVerification;
import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.monitor.ConnectionLogService;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.setup.SetupService;
import com.opnl.vpn.system.DemoSeedService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Contract tests for the /internal/** endpoints consumed by the OpenVPN helper scripts. */
class InternalControllerTest {

  private UserRepository userRepository;
  private PasswordEncoder passwordEncoder;
  private SetupService setupService;
  private AuthService authService;
  private AccessRuleService ruleService;
  private ConnectionRegistry connectionRegistry;
  private SettingsService settingsService;
  private ConnectionLogService connectionLogService;
  private DaemonService daemonService;
  private DemoSeedService demoSeedService;
  private MockMvc mvc;

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

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    setupService = mock(SetupService.class);
    authService = mock(AuthService.class);
    ruleService = mock(AccessRuleService.class);
    connectionRegistry = new ConnectionRegistry();
    settingsService = mock(SettingsService.class);
    connectionLogService = mock(ConnectionLogService.class);
    daemonService = mock(DaemonService.class);
    demoSeedService = mock(DemoSeedService.class);
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(false, false)));
    when(settingsService.effectiveForUser("u1")).thenReturn(Map.of());
    when(daemonService.ipv6Enabled(anyInt())).thenReturn(false);
    when(ruleService.iptablesFor(anyString(), anyString(), any(), anyString(), anyBoolean()))
        .thenReturn(
            new IptablesResult(
                List.of("iptables -N OPNL_x"),
                List.of("iptables -X OPNL_x"),
                List.of(),
                List.of()));

    mvc =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    ruleService,
                    connectionRegistry,
                    settingsService,
                    connectionLogService,
                    daemonService,
                    demoSeedService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void connectDeniesUnknownUser() throws Exception {
    when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"ghost\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("unknown_user"));
  }

  @Test
  void connectDeniesBannedUser() throws Exception {
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(true, false)));
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("user_banned"));
  }

  @Test
  void connectDeniesLockedUser() throws Exception {
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(false, true)));
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("user_locked"));
  }

  @Test
  void connectAllowsActiveUserAndReturnsIptables() throws Exception {
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.iptablesApply[0]").value("iptables -N OPNL_x"))
        .andExpect(jsonPath("$.iptablesRemove[0]").value("iptables -X OPNL_x"))
        .andExpect(jsonPath("$.pushes").isEmpty());
    verify(ruleService).iptablesFor("alice", "10.8.0.9", null, "u1", false);
  }

  @Test
  void connectFallsBackToUsernameWhenCommonNameBlank() throws Exception {
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(false, false)));
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"\",\"username\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true));
  }

  @Test
  void disconnectReturnsTeardownForKnownUser() throws Exception {
    mvc.perform(
            post("/internal/disconnect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remove[0]").value("iptables -X OPNL_x"));
  }

  @Test
  void disconnectReturnsEmptyForUnknownUser() throws Exception {
    when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
    mvc.perform(
            post("/internal/disconnect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"ghost\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remove").isEmpty());
  }

  @Test
  void authVerifyReturnsSetupIncompleteBeforeWizard() throws Exception {
    when(setupService.complete()).thenReturn(false);
    mvc.perform(
            post("/internal/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("setup_incomplete"));
  }

  @Test
  void authVerifyDelegatesToAuthService() throws Exception {
    when(setupService.complete()).thenReturn(true);
    when(authService.verifyVpnLogin("alice", "pass", null, "1.2.3.4"))
        .thenReturn(new VpnVerification(true, null));
    mvc.perform(
            post("/internal/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"pass\",\"remoteIp\":\"1.2.3.4\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true));
  }

  @Test
  void authVerifyOtpDelegatesToAuthService() throws Exception {
    when(setupService.complete()).thenReturn(true);
    when(authService.verifyVpnOtp("alice", "123456", "1.2.3.4"))
        .thenReturn(new VpnVerification(true, null));
    mvc.perform(
            post("/internal/auth/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"otp\":\"123456\",\"remoteIp\":\"1.2.3.4\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true));
  }

  @Test
  void seedAdminCreatesAdmin() throws Exception {
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(0L);
    when(passwordEncoder.encode("strong-pass")).thenReturn("hashed");
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    mvc.perform(
            post("/internal/seed-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"boss\",\"password\":\"strong-pass\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(true))
        .andExpect(jsonPath("$.username").value("boss"));
  }

  @Test
  void seedAdminRejectsWeakPassword() throws Exception {
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(0L);
    mvc.perform(
            post("/internal/seed-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"boss\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void seedAdminConflictsWhenAdminExists() throws Exception {
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(1L);
    mvc.perform(
            post("/internal/seed-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"boss\",\"password\":\"strong-pass\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("admin_exists"));
  }

  @Test
  void seedDemoSeedsWithForceFromBody() throws Exception {
    when(demoSeedService.seed(true)).thenReturn(4);
    mvc.perform(
            post("/internal/seed-demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"force\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users").value(4));
    verify(demoSeedService).seed(true);
  }

  @Test
  void seedDemoDefaultsToNonForceWithoutBody() throws Exception {
    when(demoSeedService.seed(false)).thenReturn(4);
    mvc.perform(post("/internal/seed-demo").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users").value(4));
    verify(demoSeedService).seed(false);
  }

  @Test
  void internalTokenFilterRejectsMissingHeader() throws Exception {
    com.opnl.vpn.config.OpnlProperties props = mock(com.opnl.vpn.config.OpnlProperties.class);
    when(props.internalToken()).thenReturn("secret");
    var filter = new com.opnl.vpn.security.InternalTokenFilter(props);
    var filtered =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    ruleService,
                    connectionRegistry,
                    settingsService,
                    connectionLogService,
                    daemonService,
                    demoSeedService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(filter)
            .build();
    filtered
        .perform(post("/internal/connect").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("invalid_internal_token"));
  }

  @Test
  void internalTokenFilterRejectsDefaultPlaceholderToken() throws Exception {
    com.opnl.vpn.config.OpnlProperties props = mock(com.opnl.vpn.config.OpnlProperties.class);
    when(props.internalToken())
        .thenReturn(com.opnl.vpn.config.OpnlProperties.DEFAULT_INTERNAL_TOKEN);
    var filter = new com.opnl.vpn.security.InternalTokenFilter(props);
    var filtered =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    ruleService,
                    connectionRegistry,
                    settingsService,
                    connectionLogService,
                    daemonService,
                    demoSeedService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(filter)
            .build();
    filtered
        .perform(
            post("/internal/connect")
                .header(
                    "X-Internal-Token", com.opnl.vpn.config.OpnlProperties.DEFAULT_INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("invalid_internal_token"));
  }

  @Test
  void internalTokenFilterAllowsMatchingToken() throws Exception {
    com.opnl.vpn.config.OpnlProperties props = mock(com.opnl.vpn.config.OpnlProperties.class);
    when(props.internalToken()).thenReturn("secret");
    var filter = new com.opnl.vpn.security.InternalTokenFilter(props);
    var filtered =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    ruleService,
                    connectionRegistry,
                    settingsService,
                    connectionLogService,
                    daemonService,
                    demoSeedService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(filter)
            .build();
    filtered
        .perform(
            post("/internal/connect")
                .header("X-Internal-Token", "secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void connectDeniesWhenMaxConnectionsReached() throws Exception {
    when(settingsService.effectiveForUser("u1")).thenReturn(Map.of("max_connections", 1));
    connectionRegistry.register("alice", "alice", "10.8.0.8", null, "9.9.9.9", "daemon-0");
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("max_connections"));
  }

  @Test
  void connectAllowsUnderMaxConnectionsLimit() throws Exception {
    when(settingsService.effectiveForUser("u1")).thenReturn(Map.of("max_connections", 2));
    connectionRegistry.register("alice", "alice", "10.8.0.8", null, "9.9.9.9", "daemon-0");
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true));
  }

  @Test
  void connectRegistersActiveSession() throws Exception {
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\",\"remoteIp\":\"1.2.3.4\",\"daemonName\":\"daemon-0\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true));

    assertThat(connectionRegistry.byVirtualIp("10.8.0.9"))
        .hasValueSatisfying(
            s -> {
              assertThat(s.username()).isEqualTo("alice");
              assertThat(s.remoteIp()).isEqualTo("1.2.3.4");
              assertThat(s.daemonName()).isEqualTo("daemon-0");
            });
  }

  @Test
  void connectRecordsSessionHistoryStart() throws Exception {
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\",\"remoteIp\":\"1.2.3.4\",\"daemonName\":\"daemon-0\"}"))
        .andExpect(status().isOk());
    verify(connectionLogService)
        .sessionStarted("alice", "alice", "10.8.0.9", "1.2.3.4", "daemon-0", null);
  }

  @Test
  void disconnectFinalizesSessionHistory() throws Exception {
    mvc.perform(
            post("/internal/disconnect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk());
    verify(connectionLogService).sessionEnded("alice");
  }

  @Test
  void disconnectUnregistersActiveSession() throws Exception {
    connectionRegistry.register("alice", "alice", "10.8.0.9", null, "1.2.3.4", "daemon-0");
    mvc.perform(
            post("/internal/disconnect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\"}"))
        .andExpect(status().isOk());
    assertThat(connectionRegistry.sessions()).isEmpty();
  }

  @Test
  void learnAddressRecordsAddressMapping() throws Exception {
    mvc.perform(
            post("/internal/learn-address")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"operation\":\"add\",\"address\":\"10.8.0.50\",\"commonName\":\"alice\"}"))
        .andExpect(status().isOk());
    assertThat(connectionRegistry.byVirtualIp("10.8.0.50"))
        .hasValueSatisfying(s -> assertThat(s.commonName()).isEqualTo("alice"));
  }

  @Test
  void learnAddressDeleteRemovesMapping() throws Exception {
    mvc.perform(
            post("/internal/learn-address")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"operation\":\"add\",\"address\":\"10.8.0.50\",\"commonName\":\"alice\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/internal/learn-address")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"operation\":\"delete\",\"address\":\"10.8.0.50\",\"commonName\":\"alice\"}"))
        .andExpect(status().isOk());
    assertThat(connectionRegistry.byVirtualIp("10.8.0.50")).isEmpty();
    assertThat(connectionRegistry.sessions()).isEmpty();
  }

  @Test
  void serializesConnectResultOmittingNullReason() throws Exception {
    String json =
        new ObjectMapper()
            .writeValueAsString(
                new InternalController.ConnectResult(
                    true, null, List.of(), List.of("a"), List.of("b"), List.of(), List.of()));
    assertThat(json).contains("\"allowed\":true").doesNotContain("reason");
  }
}
