package com.opnl.vpn.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.common.AppMeta;
import com.opnl.vpn.common.AppMetaRepository;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.ConfigWriter;
import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.network.ServerConfigGenerator;
import com.opnl.vpn.network.ServerSetting;
import com.opnl.vpn.network.ServerSettingRepository;
import com.opnl.vpn.pki.EasyRsaService;
import com.opnl.vpn.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SetupServiceTest {

  private AppMetaRepository metaRepository;
  private UserRepository userRepository;
  private ServerSettingRepository settingRepository;
  private EasyRsaService easyRsa;
  private ConfigWriter configWriter;
  private OpnlProperties properties;
  private SetupService service;
  private java.util.Map<String, String> metaStore;

  @BeforeEach
  void setUp() {
    metaRepository = mock(AppMetaRepository.class);
    userRepository = mock(UserRepository.class);
    settingRepository = mock(ServerSettingRepository.class);
    easyRsa = mock(EasyRsaService.class);
    configWriter = mock(ConfigWriter.class);
    properties = mock(OpnlProperties.class);
    OpnlProperties.OpenVpn ov = mock(OpnlProperties.OpenVpn.class);
    lenient().when(properties.openvpn()).thenReturn(ov);
    lenient().when(ov.pkiDir()).thenReturn("/pki");
    lenient().when(ov.ccdDir()).thenReturn("/ccd");
    lenient().when(ov.scriptsDir()).thenReturn("/scripts");
    lenient().when(ov.configDir()).thenReturn("/config");

    // In-memory AppMeta store so state transitions are observable.
    metaStore = new java.util.HashMap<>();
    when(metaRepository.findById(anyString()))
        .thenAnswer(
            inv ->
                Optional.ofNullable(metaStore.get(inv.getArgument(0)))
                    .map(v -> new AppMeta(inv.getArgument(0), v)));
    org.mockito.Mockito.doAnswer(
            inv -> {
              AppMeta meta = inv.getArgument(0);
              metaStore.put(meta.getKey(), meta.getValue());
              return meta;
            })
        .when(metaRepository)
        .save(any(AppMeta.class));

    service =
        new SetupService(
            metaRepository,
            userRepository,
            settingRepository,
            new BCryptPasswordEncoder(),
            easyRsa,
            new ServerConfigGenerator(new ObjectMapper()),
            configWriter,
            properties);
  }

  @Test
  void initialStateIsNotStarted() {
    assertThat(service.state()).isEqualTo(SetupService.State.NOT_STARTED);
  }

  @Test
  void fullFlowCompletes() {
    // step 1: admin
    service.runStep("admin", json("{\"username\":\"admin\",\"password\":\"supersecret1\"}"));
    verify(userRepository).save(any());
    assertThat(service.state()).isEqualTo(SetupService.State.ADMIN_DONE);

    // step 2: server
    when(settingRepository.findById("network")).thenReturn(Optional.empty());
    service.runStep("server", json(serverJson()));
    assertThat(service.state()).isEqualTo(SetupService.State.SERVER_DONE);

    // step 3: pki
    service.runStep("pki", null);
    verify(easyRsa).initPki();
    verify(easyRsa).buildServerCert("server");
    verify(easyRsa).genCrl();
    verify(configWriter).writeDaemon(any(), any(), any());
    assertThat(service.state()).isEqualTo(SetupService.State.COMPLETE);
  }

  @Test
  void adminStepRejectsWeakPassword() {
    assertThatThrownBy(
            () -> service.runStep("admin", json("{\"username\":\"a\",\"password\":\"short\"}")))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("weak_password"));
  }

  @Test
  void adminStepRejectsDuplicateUsername() {
    when(userRepository.existsByUsername("admin")).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.runStep(
                    "admin", json("{\"username\":\"admin\",\"password\":\"supersecret1\"}")))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("username_taken"));
  }

  @Test
  void outOfOrderStepsAreRejected() {
    assertThatThrownBy(() -> service.runStep("server", json(serverJson())))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("setup_state"));
  }

  @Test
  void reRunningCompletedAdminStepReturnsConflict() {
    service.runStep("admin", json("{\"username\":\"admin\",\"password\":\"supersecret1\"}"));

    assertThatThrownBy(
            () -> service.runStep("admin", json("{\"username\":\"admin\",\"password\":\"x2\"}")))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> assertThat(((ApiException) e).getCode()).isEqualTo("setup_already_started"));
  }

  @Test
  void unknownStepIsRejected() {
    assertThatThrownBy(() -> service.runStep("nonsense", null))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("setup_step"));
  }

  @Test
  void currentServerConfigFallsBackToDefaults() {
    when(settingRepository.findById("network")).thenReturn(Optional.empty());
    assertThat(service.currentServerConfig()).isEqualTo(ServerConfig.defaults());
  }

  @Test
  void currentServerConfigReadsStoredSetting() {
    when(settingRepository.findById("network"))
        .thenReturn(Optional.of(new ServerSetting("network", serverJson())));
    var config = service.currentServerConfig();
    assertThat(config.port()).isEqualTo(1194);
    assertThat(config.fullTunnel()).isTrue();
    assertThat(config.dnsServers()).containsExactly("1.1.1.1");
  }

  private String serverJson() {
    return "{\"daemonIndex\":0,\"port\":1194,\"proto\":\"udp\",\"subnet\":\"10.8.0.0\","
        + "\"subnetMask\":\"255.255.255.0\",\"dnsServers\":[\"1.1.1.1\"],\"fullTunnel\":true,"
        + "\"clientCertNotRequired\":false,\"adminHost\":\"vpn.example.com\"}";
  }

  private com.fasterxml.jackson.databind.JsonNode json(String raw) {
    try {
      return new ObjectMapper().readTree(raw);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
