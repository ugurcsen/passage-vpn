package com.passagevpn.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.auth.AuthService;
import com.passagevpn.auth.AuthService.VpnVerification;
import com.passagevpn.common.GlobalExceptionHandler;
import com.passagevpn.network.ConnectionRegistry;
import com.passagevpn.security.SeedGuard;
import com.passagevpn.setup.SetupService;
import com.passagevpn.system.DemoSeedService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.util.List;
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
  private ConnectionOrchestrator orchestrator;
  private ConnectionRegistry connectionRegistry;
  private DemoSeedService demoSeedService;
  private SeedGuard seedGuard;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    setupService = mock(SetupService.class);
    authService = mock(AuthService.class);
    orchestrator = mock(ConnectionOrchestrator.class);
    connectionRegistry = new ConnectionRegistry();
    demoSeedService = mock(DemoSeedService.class);
    seedGuard = mock(SeedGuard.class);

    mvc =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    orchestrator,
                    connectionRegistry,
                    demoSeedService,
                    seedGuard))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void connectDelegatesToOrchestratorAndReturnsResult() throws Exception {
    when(orchestrator.connect("alice", null, "10.8.0.9", null, null, "daemon-0", null))
        .thenReturn(
            new ConnectionOrchestrator.ConnectResult(
                true, null, List.of(), List.of("apply"), List.of("remove"), List.of(), List.of()));
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\",\"daemonName\":\"daemon-0\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.iptablesApply[0]").value("apply"))
        .andExpect(jsonPath("$.iptablesRemove[0]").value("remove"));
  }

  @Test
  void connectDelegatesDenyFromOrchestrator() throws Exception {
    when(orchestrator.connect("ghost", null, null, null, null, null, null))
        .thenReturn(ConnectionOrchestrator.ConnectResult.deny("unknown_user"));
    mvc.perform(
            post("/internal/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commonName\":\"ghost\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("unknown_user"));
  }

  @Test
  void disconnectDelegatesToOrchestratorAndReturnsResult() throws Exception {
    when(orchestrator.disconnect("alice", "10.8.0.9", null, "daemon-0"))
        .thenReturn(new ConnectionOrchestrator.DisconnectResult(List.of("teardown"), List.of()));
    mvc.perform(
            post("/internal/disconnect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"commonName\":\"alice\",\"virtualIp\":\"10.8.0.9\",\"daemonName\":\"daemon-0\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remove[0]").value("teardown"))
        .andExpect(jsonPath("$.remove6").isEmpty());
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
    when(authService.verifyVpnOtp("alice", "123456", "1.2.3.4", "pending-1"))
        .thenReturn(new VpnVerification(true, null));
    mvc.perform(
            post("/internal/auth/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"alice\",\"otp\":\"123456\",\"remoteIp\":\"1.2.3.4\",\"pendingId\":\"pending-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true));
  }

  @Test
  void authVerifyOtpDeniedWithoutPendingId() throws Exception {
    when(setupService.complete()).thenReturn(true);
    when(authService.verifyVpnOtp("alice", "123456", "1.2.3.4", null))
        .thenReturn(new VpnVerification(false, "missing_pending"));
    mvc.perform(
            post("/internal/auth/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"otp\":\"123456\",\"remoteIp\":\"1.2.3.4\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("missing_pending"))
        .andExpect(jsonPath("$.pendingId").doesNotExist());
  }

  @Test
  void authVerifyNormalizesLockedAccountReason() throws Exception {
    when(setupService.complete()).thenReturn(true);
    when(authService.verifyVpnLogin("alice", "pass", null, "1.2.3.4"))
        .thenReturn(new VpnVerification(false, "account_locked"));
    mvc.perform(
            post("/internal/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"pass\",\"remoteIp\":\"1.2.3.4\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("invalid_credentials"));
  }

  @Test
  void authVerifyExposesPendingIdForMfaPendingFlow() throws Exception {
    when(setupService.complete()).thenReturn(true);
    when(authService.verifyVpnLogin("alice", "pass", null, "1.2.3.4"))
        .thenReturn(new VpnVerification(false, "mfa_required", "pending-1"));
    mvc.perform(
            post("/internal/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"pass\",\"remoteIp\":\"1.2.3.4\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("mfa_required"))
        .andExpect(jsonPath("$.pendingId").value("pending-1"));
  }

  @Test
  void seedEndpointsRequireBootstrapTokenWhenConfigured() throws Exception {
    var guarded =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    orchestrator,
                    connectionRegistry,
                    demoSeedService,
                    new SeedGuard("bootstrap-secret")))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    guarded
        .perform(post("/internal/seed-admin").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("bootstrap_token_required"));
    guarded
        .perform(
            post("/internal/seed-demo")
                .header("X-Bootstrap-Token", "bootstrap-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
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
    com.passagevpn.config.PassageProperties props =
        mock(com.passagevpn.config.PassageProperties.class);
    when(props.internalToken()).thenReturn("secret");
    var filter = new com.passagevpn.security.InternalTokenFilter(props);
    var filtered =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    orchestrator,
                    connectionRegistry,
                    demoSeedService,
                    seedGuard))
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
    com.passagevpn.config.PassageProperties props =
        mock(com.passagevpn.config.PassageProperties.class);
    when(props.internalToken())
        .thenReturn(com.passagevpn.config.PassageProperties.DEFAULT_INTERNAL_TOKEN);
    var filter = new com.passagevpn.security.InternalTokenFilter(props);
    var filtered =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    orchestrator,
                    connectionRegistry,
                    demoSeedService,
                    seedGuard))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(filter)
            .build();
    filtered
        .perform(
            post("/internal/connect")
                .header(
                    "X-Internal-Token",
                    com.passagevpn.config.PassageProperties.DEFAULT_INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("invalid_internal_token"));
  }

  @Test
  void internalTokenFilterAllowsMatchingToken() throws Exception {
    when(orchestrator.connect(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ConnectionOrchestrator.ConnectResult(
                true, null, List.of(), List.of(), List.of(), List.of(), List.of()));
    com.passagevpn.config.PassageProperties props =
        mock(com.passagevpn.config.PassageProperties.class);
    when(props.internalToken()).thenReturn("secret");
    var filter = new com.passagevpn.security.InternalTokenFilter(props);
    var filtered =
        MockMvcBuilders.standaloneSetup(
                new InternalController(
                    userRepository,
                    passwordEncoder,
                    setupService,
                    authService,
                    orchestrator,
                    connectionRegistry,
                    demoSeedService,
                    seedGuard))
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
