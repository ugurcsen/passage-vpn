package com.opnl.vpn.ccd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.network.ServerConfigGenerator;
import com.opnl.vpn.network.ServerSettingRepository;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CcdServiceTest {

  private UserRepository userRepository;
  private SettingsService settingsService;
  private ServerSettingRepository serverSettingRepository;
  private ServerConfigGenerator serverConfigGenerator;
  private CcdService service;

  @BeforeEach
  void setUp() throws Exception {
    java.nio.file.Path dir = java.nio.file.Path.of("/tmp/opnl-ccd-test");
    if (java.nio.file.Files.isDirectory(dir)) {
      try (var stream = java.nio.file.Files.list(dir)) {
        stream.forEach(p -> p.toFile().delete());
      }
    }
    userRepository = mock(UserRepository.class);
    settingsService = mock(SettingsService.class);
    serverSettingRepository = mock(ServerSettingRepository.class);
    serverConfigGenerator = mock(ServerConfigGenerator.class);
    OpnlProperties properties = mock(OpnlProperties.class);
    OpnlProperties.OpenVpn openvpn = mock(OpnlProperties.OpenVpn.class);
    when(properties.openvpn()).thenReturn(openvpn);
    when(openvpn.ccdDir()).thenReturn("/tmp/opnl-ccd-test");
    when(serverConfigGenerator.fromJson(any())).thenReturn(ServerConfig.defaults());
    service =
        new CcdService(
            userRepository,
            settingsService,
            serverSettingRepository,
            serverConfigGenerator,
            properties);
  }

  private User user(String id, String username, String staticIp) {
    return User.builder()
        .id(id)
        .username(username)
        .role(User.Role.USER)
        .createdAt(Instant.now())
        .staticIp(staticIp)
        .build();
  }

  private void poolFor(String... pools) {
    Map<String, Object> settings = Map.of();
    if (pools.length == 1 && pools[0] != null) {
      settings = Map.of(SettingKeys.STATIC_IP_POOL, pools[0]);
    }
    when(settingsService.groupChainForUser(anyString())).thenReturn(List.of("group1"));
    when(settingsService.groupSettings("group1")).thenReturn(settings);
  }

  private void effectiveFor(Map<String, Object> settings) {
    when(settingsService.effectiveForUser(anyString())).thenReturn(settings);
  }

  private User savedUser(String id, String username, String staticIp) {
    User user = user(id, username, staticIp);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    return user;
  }

  private java.nio.file.Path ccdPath(String commonName) {
    return java.nio.file.Path.of("/tmp/opnl-ccd-test/" + commonName);
  }

  @Test
  void allocateFromGroupPoolAssignsFirstFreeIp() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(userRepository.findAll()).thenReturn(List.of(alice));
    poolFor("10.8.0.100-10.8.0.101");

    String ip = service.allocateFromGroupPool("u1");

    assertThat(ip).isEqualTo("10.8.0.100");
    assertThat(alice.getStaticIp()).isEqualTo("10.8.0.100");
    verify(userRepository).save(alice);
  }

  @Test
  void allocateFromGroupPoolSkipsUsedAddresses() {
    User alice = user("u1", "alice", null);
    User bob = user("u2", "bob", "10.8.0.100");
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(userRepository.findAll()).thenReturn(List.of(alice, bob));
    poolFor("10.8.0.100-10.8.0.101");

    String ip = service.allocateFromGroupPool("u1");

    assertThat(ip).isEqualTo("10.8.0.101");
  }

  @Test
  void allocateFromGroupPoolKeepsExistingIpWhenStillFree() {
    User alice = user("u1", "alice", "10.8.0.100");
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(userRepository.findAll()).thenReturn(List.of(alice));
    poolFor("10.8.0.100-10.8.0.101");

    String ip = service.allocateFromGroupPool("u1");

    assertThat(ip).isEqualTo("10.8.0.100");
  }

  @Test
  void allocateFromGroupPoolThrowsWhenPoolExhausted() {
    User alice = user("u1", "alice", null);
    User bob = user("u2", "bob", "10.8.0.100");
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(userRepository.findAll()).thenReturn(List.of(alice, bob));
    poolFor("10.8.0.100-10.8.0.100");

    assertThatThrownBy(() -> service.allocateFromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("pool_exhausted"));
  }

  @Test
  void allocateFromGroupPoolThrowsWhenNoPoolConfigured() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    poolFor((String) null);

    assertThatThrownBy(() -> service.allocateFromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("no_ip_pool"));
  }

  @Test
  void allocateFromGroupPoolThrowsWhenPoolMalformed() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    poolFor("not-an-ip");

    assertThatThrownBy(() -> service.allocateFromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void allocateFromGroupPoolThrowsWhenStartGreaterThanEnd() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    poolFor("10.8.0.200-10.8.0.100");

    assertThatThrownBy(() -> service.allocateFromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void validatePoolAcceptsBlank() {
    service.validatePool(null);
    service.validatePool("  ");
  }

  @Test
  void validatePoolRejectsMalformed() {
    assertThatThrownBy(() -> service.validatePool("10.8.0.100"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void validatePoolRejectsNetworkOrBroadcastEndpoint() {
    assertThatThrownBy(() -> service.validatePool("10.8.0.0-10.8.0.10"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
    assertThatThrownBy(() -> service.validatePool("10.8.0.1-10.8.0.255"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void validatePoolRejectsRangeOutsideSubnet() {
    assertThatThrownBy(() -> service.validatePool("10.9.0.1-10.9.0.10"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void allocateFromGroupPoolRejectsPoolSpanningNetworkAddress() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    poolFor("10.8.0.0-10.8.0.5");

    assertThatThrownBy(() -> service.allocateFromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void clearStaticIpRemovesIpAndDeletesCcd() {
    User alice = user("u1", "alice", "10.8.0.100");
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));

    service.clearStaticIp("u1");

    assertThat(alice.getStaticIp()).isNull();
    verify(userRepository).save(alice);
  }

  @Test
  void writeUserCcdWithoutStaticIpDeletesFile() {
    User alice = user("u1", "alice", null);
    service.writeUserCcd(alice);
    assertThat(alice.getStaticIp()).isNull();
  }

  @Test
  void writeUserCcdFullTunnelPushesRedirectGateway() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    effectiveFor(Map.of(SettingKeys.TUNNEL_MODE, "full"));

    service.writeUserCcd(alice);

    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).contains("ifconfig-push 10.8.0.100 255.255.255.0");
    assertThat(lines).contains("push \"redirect-gateway def1 bypass-dhcp\"");
  }

  @Test
  void writeUserCcdSplitTunnelPushesRoutesNotRedirectGateway() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    effectiveFor(
        Map.of(
            SettingKeys.TUNNEL_MODE,
            "split",
            SettingKeys.ROUTE_RESTRICTION,
            "10.0.0.0/8, 192.168.0.0/16"));

    service.writeUserCcd(alice);

    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).contains("push \"route 10.0.0.0/8\"", "push \"route 192.168.0.0/16\"");
    assertThat(lines).doesNotContain("push \"redirect-gateway def1 bypass-dhcp\"");
  }

  @Test
  void writeUserCcdFallsBackToServerFullTunnelWhenModeUnset() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    effectiveFor(Map.of());

    service.writeUserCcd(alice);

    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).contains("push \"redirect-gateway def1 bypass-dhcp\"");
  }

  @Test
  void writeUserCcdSplitTunnelWithoutRoutesIsMinimal() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    effectiveFor(Map.of(SettingKeys.TUNNEL_MODE, "split"));

    service.writeUserCcd(alice);

    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).containsExactly("ifconfig-push 10.8.0.100 255.255.255.0");
  }
}
