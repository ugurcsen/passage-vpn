package com.opnl.vpn.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.ServerConfig.Protocol;
import com.opnl.vpn.profile.ProfileType;
import com.opnl.vpn.setting.SettingKeys;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DaemonServiceTest {

  private DaemonRepository repository;
  private ServerSettingRepository settingRepository;
  private ConfigWriter configWriter;
  private NodeRegistryService nodeRegistryService;
  private OpnlProperties properties;
  private DaemonService service;

  private Daemon primary() {
    return daemon("d0", 0, "Primary", 1194, "10.8.0.0", false, true);
  }

  private Daemon daemon(
      String id, int index, String name, int port, String subnet, boolean ccnr, boolean authUp) {
    return Daemon.builder()
        .id(id)
        .daemonIndex(index)
        .name(name)
        .port(port)
        .proto(Protocol.udp)
        .subnet(subnet)
        .subnetMask("255.255.255.0")
        .dnsServers(List.of("1.1.1.1"))
        .fullTunnel(true)
        .clientCertNotRequired(ccnr)
        .authUserPass(authUp)
        .adminHost("vpn.example.com")
        .enabled(true)
        .createdAt(Instant.now())
        .build();
  }

  private void stubNonEmpty() {
    when(repository.count()).thenReturn(1L);
    when(repository.findByDaemonIndex(0)).thenReturn(Optional.of(primary()));
    when(repository.findAllByOrderByDaemonIndexAsc()).thenAnswer(inv -> List.of(primary()));
  }

  @BeforeEach
  void setUp() {
    repository = mock(DaemonRepository.class);
    settingRepository = mock(ServerSettingRepository.class);
    configWriter = mock(ConfigWriter.class);
    nodeRegistryService = mock(NodeRegistryService.class);
    properties = mock(OpnlProperties.class);
    service =
        new DaemonService(
            repository,
            settingRepository,
            new ServerConfigGenerator(new ObjectMapper()),
            configWriter,
            properties,
            mock(com.opnl.vpn.audit.AuditLogService.class),
            nodeRegistryService);
  }

  @Test
  void listSeedsPrimaryFromLegacySettingWhenEmpty() {
    when(repository.count()).thenReturn(0L);
    when(repository.findByDaemonIndex(0)).thenReturn(Optional.empty());
    when(settingRepository.findById("network"))
        .thenReturn(
            Optional.of(
                new ServerSetting(
                    "network",
                    "{\"daemonIndex\":0,\"port\":1194,\"proto\":\"udp\",\"subnet\":\"10.8.0.0\","
                        + "\"subnetMask\":\"255.255.255.0\",\"dnsServers\":[\"1.1.1.1\"],"
                        + "\"fullTunnel\":true,\"clientCertNotRequired\":false,"
                        + "\"authUserPass\":true,\"adminHost\":\"vpn.example.com\"}")));
    when(repository.save(any(Daemon.class))).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findAllByOrderByDaemonIndexAsc()).thenReturn(List.of());

    service.list();

    ArgumentCaptor<Daemon> captor = ArgumentCaptor.forClass(Daemon.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getDaemonIndex()).isZero();
    assertThat(captor.getValue().getName()).isEqualTo("Primary");
    assertThat(captor.getValue().getPort()).isEqualTo(1194);
    assertThat(captor.getValue().isAuthUserPass()).isTrue();
  }

  @Test
  void listWithoutLegacySettingFallsBackToDefaults() {
    when(repository.count()).thenReturn(0L);
    when(repository.findByDaemonIndex(0)).thenReturn(Optional.empty());
    when(settingRepository.findById("network")).thenReturn(Optional.empty());
    when(repository.save(any(Daemon.class))).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findAllByOrderByDaemonIndexAsc()).thenReturn(List.of());

    service.list();

    ArgumentCaptor<Daemon> captor = ArgumentCaptor.forClass(Daemon.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getPort()).isEqualTo(1194);
    assertThat(captor.getValue().getSubnet()).isEqualTo("10.8.0.0");
  }

  @Test
  void resolveGenericUsesCertNotRequiredDaemon() {
    Daemon generic = daemon("g1", 1, "Generic", 1195, "10.9.0.0", true, true);
    stubNonEmpty();
    when(repository.findByEnabledTrueOrderByDaemonIndexAsc())
        .thenReturn(List.of(primary(), generic));

    ServerConfig config = service.resolveForProfile(ProfileType.GENERIC);

    assertThat(config.port()).isEqualTo(1195);
    assertThat(config.daemonIndex()).isEqualTo(1);
    assertThat(config.clientCertNotRequired()).isTrue();
  }

  @Test
  void resolveAutoLoginUsesCertOnlyDaemon() {
    Daemon certOnly = daemon("a1", 1, "Auto-login", 1196, "10.10.0.0", false, false);
    stubNonEmpty();
    when(repository.findByEnabledTrueOrderByDaemonIndexAsc())
        .thenReturn(List.of(primary(), certOnly));

    ServerConfig config = service.resolveForProfile(ProfileType.AUTO_LOGIN);

    assertThat(config.port()).isEqualTo(1196);
    assertThat(config.clientCertNotRequired()).isFalse();
    assertThat(config.authUserPass()).isFalse();
  }

  @Test
  void resolveLockedUsesPasswordDaemon() {
    stubNonEmpty();
    when(repository.findByEnabledTrueOrderByDaemonIndexAsc()).thenReturn(List.of(primary()));

    ServerConfig userLocked = service.resolveForProfile(ProfileType.USER_LOCKED);
    ServerConfig serverLocked = service.resolveForProfile(ProfileType.SERVER_LOCKED);

    assertThat(userLocked.port()).isEqualTo(1194);
    assertThat(serverLocked.port()).isEqualTo(1194);
    assertThat(userLocked.authUserPass()).isTrue();
  }

  @Test
  void resolveFallsBackToPrimaryWhenNoMatch() {
    stubNonEmpty();
    when(repository.findByEnabledTrueOrderByDaemonIndexAsc()).thenReturn(List.of(primary()));

    ServerConfig config = service.resolveForProfile(ProfileType.GENERIC);

    assertThat(config.port()).isEqualTo(1194);
  }

  @Test
  void createRejectsDuplicateDaemonIndex() {
    when(repository.findByDaemonIndex(0)).thenReturn(Optional.of(primary()));

    assertThatThrownBy(
            () ->
                service.create(
                    new DaemonService.DaemonRequest(
                        0,
                        "dup",
                        9999,
                        Protocol.udp,
                        "10.9.0.0",
                        "255.255.255.0",
                        List.of(),
                        null,
                        List.of(),
                        true,
                        false,
                        true,
                        null,
                        null,
                        false,
                        null,
                        true)))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "daemon_index_taken");
  }

  @Test
  void createRejectsDuplicatePort() {
    when(repository.findByDaemonIndex(1)).thenReturn(Optional.empty());
    when(repository.findAll()).thenReturn(List.of(primary()));

    assertThatThrownBy(
            () ->
                service.create(
                    new DaemonService.DaemonRequest(
                        1,
                        "dup",
                        1194,
                        Protocol.udp,
                        "10.9.0.0",
                        "255.255.255.0",
                        List.of(),
                        null,
                        List.of(),
                        true,
                        false,
                        true,
                        null,
                        null,
                        false,
                        null,
                        true)))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "daemon_port_taken");
  }

  @Test
  void createWritesAllConfigsAfterSave() {
    when(repository.findByDaemonIndex(1)).thenReturn(Optional.empty());
    when(repository.findAll()).thenReturn(List.of(primary()));
    when(repository.save(any(Daemon.class))).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findAllByOrderByDaemonIndexAsc()).thenReturn(List.of(primary()));
    when(settingRepository.findById(SettingKeys.NETWORK_MODE)).thenReturn(Optional.empty());

    service.create(
        new DaemonService.DaemonRequest(
            1,
            "Generic",
            1195,
            Protocol.udp,
            "10.9.0.0",
            "255.255.255.0",
            List.of(),
            null,
            List.of(),
            true,
            true,
            true,
            null,
            null,
            false,
            null,
            true));

    verify(configWriter).writeDaemon(any(), any(), any(), any());
  }

  @Test
  void deleteRejectsPrimary() {
    when(repository.findById("d0")).thenReturn(Optional.of(primary()));

    assertThatThrownBy(() -> service.delete("d0"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "primary_daemon");
  }

  @Test
  void deleteRemovesConfigFileForNonPrimary() {
    Daemon extra = daemon("d1", 1, "Extra", 1195, "10.9.0.0", true, true);
    when(repository.findById("d1")).thenReturn(Optional.of(extra));

    service.delete("d1");

    verify(repository).delete(extra);
    verify(configWriter).removeDaemon(1);
  }

  @Test
  void writeAllWritesEnabledAndRemovesDisabled() {
    Daemon disabled = daemon("d1", 1, "Offline", 1195, "10.9.0.0", true, true);
    disabled.setEnabled(false);
    when(repository.findByDaemonIndex(0)).thenReturn(Optional.of(primary()));
    when(repository.findAllByOrderByDaemonIndexAsc()).thenReturn(List.of(primary(), disabled));
    when(settingRepository.findById(SettingKeys.NETWORK_MODE)).thenReturn(Optional.empty());

    service.writeAll();

    verify(configWriter).writeDaemon(any(), any(), any(), eq("nat"));
    verify(configWriter).removeDaemon(1);
  }

  @Test
  void writeAllPassesRoutedNetworkModeToConfigWriter() {
    when(repository.findByDaemonIndex(0)).thenReturn(Optional.of(primary()));
    when(repository.findAllByOrderByDaemonIndexAsc()).thenReturn(List.of(primary()));
    when(settingRepository.findById(SettingKeys.NETWORK_MODE))
        .thenReturn(
            Optional.of(
                ServerSetting.builder().key(SettingKeys.NETWORK_MODE).value("\"routed\"").build()));

    service.writeAll();

    verify(configWriter).writeDaemon(any(), any(), any(), eq("routed"));
  }

  @Test
  void writeAllDefaultsToNatForUnrecognizedNetworkMode() {
    when(repository.findByDaemonIndex(0)).thenReturn(Optional.of(primary()));
    when(repository.findAllByOrderByDaemonIndexAsc()).thenReturn(List.of(primary()));
    when(settingRepository.findById(SettingKeys.NETWORK_MODE))
        .thenReturn(
            Optional.of(
                ServerSetting.builder().key(SettingKeys.NETWORK_MODE).value("bridged").build()));

    service.writeAll();

    verify(configWriter).writeDaemon(any(), any(), any(), eq("nat"));
  }

  @Test
  void resolveAllForProfileReturnsEveryMatchingDaemon() {
    Daemon second = daemon("d1", 1, "Second", 1195, "10.9.0.0", false, true);
    stubNonEmpty();
    when(repository.findByEnabledTrueOrderByDaemonIndexAsc())
        .thenReturn(List.of(primary(), second));

    List<DaemonService.ProfileEndpoint> endpoints =
        service.resolveAllForProfile(ProfileType.USER_LOCKED);

    assertThat(endpoints).hasSize(2);
    assertThat(endpoints.get(0).config().port()).isEqualTo(1194);
    assertThat(endpoints.get(1).config().port()).isEqualTo(1195);
  }

  @Test
  void resolveAllForProfileFallsBackToPrimaryWhenNoMatch() {
    stubNonEmpty();
    when(repository.findByEnabledTrueOrderByDaemonIndexAsc()).thenReturn(List.of(primary()));

    List<DaemonService.ProfileEndpoint> endpoints =
        service.resolveAllForProfile(ProfileType.AUTO_LOGIN);

    assertThat(endpoints).hasSize(1);
    assertThat(endpoints.get(0).config().port()).isEqualTo(1194);
  }

  @Test
  void resolveAllForProfileSkipsDaemonsOnDisabledNodes() {
    stubNonEmpty();
    Daemon remote = daemon("r1", 1, "Remote", 1195, "10.9.0.0", false, true);
    remote.setNodeId("n1");
    OpenVpnNode node = new OpenVpnNode();
    node.setEnabled(false);
    when(nodeRegistryService.findNode("n1")).thenReturn(Optional.of(node));
    when(repository.findByEnabledTrueOrderByDaemonIndexAsc())
        .thenReturn(List.of(primary(), remote));

    List<DaemonService.ProfileEndpoint> endpoints =
        service.resolveAllForProfile(ProfileType.USER_LOCKED);

    assertThat(endpoints).hasSize(1);
    assertThat(endpoints.get(0).config().port()).isEqualTo(1194);
  }

  @Test
  void effectiveAdminHostPrefersDaemonThenNodeThenGlobal() {
    OpnlProperties.OpenVpn openvpn = mock(OpnlProperties.OpenVpn.class);
    when(properties.openvpn()).thenReturn(openvpn);

    OpenVpnNode node = new OpenVpnNode();
    node.setAdminHost("node.example.com");
    when(nodeRegistryService.findNode("n1")).thenReturn(Optional.of(node));

    Daemon remote = daemon("r1", 1, "Remote", 1195, "10.9.0.0", false, true);
    remote.setAdminHost(null);
    remote.setNodeId("n1");

    assertThat(service.effectiveAdminHost(remote)).isEqualTo("node.example.com");

    remote.setAdminHost("daemon.example.com");
    assertThat(service.effectiveAdminHost(remote)).isEqualTo("daemon.example.com");

    remote.setAdminHost(null);
    node.setAdminHost(null);
    when(openvpn.adminHost()).thenReturn("global.example.com");
    assertThat(service.effectiveAdminHost(remote)).isEqualTo("global.example.com");
  }

  @Test
  void effectiveAdminHostIgnoresNodeHostForLocalDaemons() {
    OpnlProperties.OpenVpn openvpn = mock(OpnlProperties.OpenVpn.class);
    when(properties.openvpn()).thenReturn(openvpn);
    when(openvpn.adminHost()).thenReturn("global.example.com");

    Daemon local = daemon("d0", 0, "Primary", 1194, "10.8.0.0", false, true);
    local.setAdminHost(null);
    local.setNodeId(null);

    assertThat(service.effectiveAdminHost(local)).isEqualTo("global.example.com");
  }
}
