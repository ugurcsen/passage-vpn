package com.passagevpn.ccd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.common.ApiException;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.network.ServerConfig;
import com.passagevpn.network.ServerConfigGenerator;
import com.passagevpn.network.ServerSettingRepository;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
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
    PassageProperties properties = mock(PassageProperties.class);
    PassageProperties.OpenVpn openvpn = mock(PassageProperties.OpenVpn.class);
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

  private void dualStack() {
    when(serverConfigGenerator.fromJson(any()))
        .thenReturn(
            new ServerConfig(
                0,
                1194,
                ServerConfig.Protocol.udp,
                "10.8.0.0",
                "255.255.255.0",
                List.of("1.1.1.1"),
                null,
                List.of(),
                true,
                false,
                true,
                "vpn.example.com",
                true,
                "fd00:1::/64"));
  }

  private void poolFor6(String... pools) {
    Map<String, Object> settings = Map.of();
    if (pools.length == 1 && pools[0] != null) {
      settings = Map.of(SettingKeys.STATIC_IPV6_POOL, pools[0]);
    }
    when(settingsService.groupChainForUser(anyString())).thenReturn(List.of("group1"));
    when(settingsService.groupSettings("group1")).thenReturn(settings);
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

  @Test
  void setStaticIpWritesCcdFile() throws Exception {
    User alice = savedUser("u1", "alice", null);

    service.setStaticIp("u1", "10.8.0.100");

    assertThat(alice.getStaticIp()).isEqualTo("10.8.0.100");
    verify(userRepository).save(alice);
    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).contains("ifconfig-push 10.8.0.100 255.255.255.0");
  }

  @Test
  void setStaticIpWithBlankIpClearsStaticIp() {
    User alice = savedUser("u1", "alice", "10.8.0.100");

    service.setStaticIp("u1", "  ");

    assertThat(alice.getStaticIp()).isNull();
    verify(userRepository).save(alice);
  }

  @Test
  void setStaticIpThrowsWhenIpInUseByAnotherUser() {
    User bob = user("u2", "bob", "10.8.0.100");
    when(userRepository.findByStaticIp("10.8.0.100")).thenReturn(Optional.of(bob));

    assertThatThrownBy(() -> service.setStaticIp("u1", "10.8.0.100"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("static_ip_in_use"));
  }

  @Test
  void setStaticIpAllowsReassigningOwnIp() {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    when(userRepository.findByStaticIp("10.8.0.100")).thenReturn(Optional.of(alice));

    service.setStaticIp("u1", "10.8.0.100");

    assertThat(alice.getStaticIp()).isEqualTo("10.8.0.100");
    verify(userRepository).save(alice);
  }

  @Test
  void setStaticIpThrowsWhenUserNotFound() {
    assertThatThrownBy(() -> service.setStaticIp("u1", "10.8.0.100"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found"));
  }

  @Test
  void setStaticIpThrowsOnInvalidIp() {
    assertThatThrownBy(() -> service.setStaticIp("u1", "not-an-ip"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ip"));
  }

  @Test
  void setStaticIpThrowsWhenOutsideSubnet() {
    assertThatThrownBy(() -> service.setStaticIp("u1", "192.168.1.5"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ip"));
  }

  @Test
  void setStaticIpThrowsOnNetworkOrBroadcastAddress() {
    assertThatThrownBy(() -> service.setStaticIp("u1", "10.8.0.0"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ip"));
    assertThatThrownBy(() -> service.setStaticIp("u1", "10.8.0.255"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ip"));
  }

  @Test
  void setStaticIpThrowsWhenSubnetUnresolvable() {
    when(serverConfigGenerator.fromJson(any()))
        .thenReturn(
            new ServerConfig(
                0,
                1194,
                ServerConfig.Protocol.udp,
                "not-a-subnet",
                "255.255.255.0",
                List.of(),
                null,
                List.of(),
                true,
                false,
                true,
                "vpn.example.com",
                false,
                null));

    assertThatThrownBy(() -> service.setStaticIp("u1", "10.8.0.5"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ip"));
  }

  @Test
  void clearStaticIpThrowsWhenUserNotFound() {
    assertThatThrownBy(() -> service.clearStaticIp("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found"));
  }

  @Test
  void allocateFromGroupPoolThrowsWhenUserNotFound() {
    assertThatThrownBy(() -> service.allocateFromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found"));
  }

  @Test
  void allocateFromGroupPoolThrowsWhenPoolRangeTooLarge() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(serverConfigGenerator.fromJson(any()))
        .thenReturn(
            new ServerConfig(
                0,
                1194,
                ServerConfig.Protocol.udp,
                "10.0.0.0",
                "255.0.0.0",
                List.of(),
                null,
                List.of(),
                true,
                false,
                true,
                "vpn.example.com",
                false,
                null));
    poolFor("10.1.0.1-10.2.0.1");

    assertThatThrownBy(() -> service.allocateFromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void groupPoolForReturnsMostSpecificGroupPool() {
    when(settingsService.groupChainForUser("u1")).thenReturn(List.of("group1", "group2"));
    when(settingsService.groupSettings("group1")).thenReturn(Map.of());
    when(settingsService.groupSettings("group2"))
        .thenReturn(Map.of(SettingKeys.STATIC_IP_POOL, "10.8.0.100-10.8.0.199"));

    assertThat(service.groupPoolFor("u1")).isEqualTo("10.8.0.100-10.8.0.199");
  }

  @Test
  void groupPoolForSkipsBlankPools() {
    when(settingsService.groupChainForUser("u1")).thenReturn(List.of("group1"));
    when(settingsService.groupSettings("group1"))
        .thenReturn(Map.of(SettingKeys.STATIC_IP_POOL, "  "));

    assertThat(service.groupPoolFor("u1")).isNull();
  }

  @Test
  void groupPoolForReturnsNullWithoutPools() {
    when(settingsService.groupChainForUser("u1")).thenReturn(List.of("group1"));
    when(settingsService.groupSettings("group1")).thenReturn(Map.of());

    assertThat(service.groupPoolFor("u1")).isNull();
  }

  @Test
  void validatePoolRejectsIpv6Endpoints() {
    assertThatThrownBy(() -> service.validatePool("fd00:1::10-fd00:1::ff"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void validatePoolRejectsReservedAddresses() {
    assertThatThrownBy(() -> service.validatePool("0.0.0.0-10.8.0.5"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
    assertThatThrownBy(() -> service.validatePool("10.8.0.1-255.255.255.255"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void validatePoolRejectsUnresolvableSubnet() {
    when(serverConfigGenerator.fromJson(any())).thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(() -> service.validatePool("10.8.0.10-10.8.0.20"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void setStaticIpv6WritesCcdFile() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    dualStack();

    service.setStaticIpv6("u1", "fd00:1::10");

    assertThat(alice.getStaticIpv6()).isEqualTo("fd00:1::10");
    verify(userRepository).save(alice);
    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).contains("ifconfig-ipv6-push fd00:1::10/64 fd00:1:0:0:0:0:0:1");
  }

  @Test
  void setStaticIpv6WithBlankIpClearsStaticIpv6() {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    alice.setStaticIpv6("fd00:1::10");
    dualStack();

    service.setStaticIpv6("u1", "  ");

    assertThat(alice.getStaticIpv6()).isNull();
    verify(userRepository).save(alice);
  }

  @Test
  void setStaticIpv6ThrowsWhenIpv6Disabled() {
    savedUser("u1", "alice", null);

    assertThatThrownBy(() -> service.setStaticIpv6("u1", "fd00:1::10"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ipv6_disabled"));
  }

  @Test
  void setStaticIpv6ThrowsOnInvalidAddress() {
    savedUser("u1", "alice", null);
    dualStack();

    assertThatThrownBy(() -> service.setStaticIpv6("u1", "not-an-ip"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ipv6"));
  }

  @Test
  void setStaticIpv6ThrowsWhenOutsideSubnet() {
    savedUser("u1", "alice", null);
    dualStack();

    assertThatThrownBy(() -> service.setStaticIpv6("u1", "fd00:2::10"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ipv6"));
  }

  @Test
  void setStaticIpv6ThrowsOnNetworkServerOrBroadcast() {
    savedUser("u1", "alice", null);
    dualStack();

    assertThatThrownBy(() -> service.setStaticIpv6("u1", "fd00:1::"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ipv6"));
    assertThatThrownBy(() -> service.setStaticIpv6("u1", "fd00:1::1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ipv6"));
    assertThatThrownBy(() -> service.setStaticIpv6("u1", "fd00:1::ffff:ffff:ffff:ffff"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_static_ipv6"));
  }

  @Test
  void setStaticIpv6ThrowsWhenInUseByAnotherUser() {
    User bob = user("u2", "bob", null);
    when(userRepository.findByStaticIpv6("fd00:1::10")).thenReturn(Optional.of(bob));
    dualStack();

    assertThatThrownBy(() -> service.setStaticIpv6("u1", "fd00:1::10"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("static_ipv6_in_use"));
  }

  @Test
  void clearStaticIpv6KeepsIpv4PushInCcd() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    alice.setStaticIpv6("fd00:1::10");
    dualStack();

    service.clearStaticIpv6("u1");

    assertThat(alice.getStaticIpv6()).isNull();
    verify(userRepository).save(alice);
    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).contains("ifconfig-push 10.8.0.100 255.255.255.0");
    assertThat(lines).doesNotContain("ifconfig-ipv6-push");
  }

  @Test
  void clearStaticIpv6ThrowsWhenUserNotFound() {
    assertThatThrownBy(() -> service.clearStaticIpv6("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found"));
  }

  @Test
  void allocateIpv6FromGroupPoolAssignsFirstFreeAddress() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(userRepository.findAll()).thenReturn(List.of(alice));
    dualStack();
    poolFor6("fd00:1::10-fd00:1::11");

    String ip = service.allocateIpv6FromGroupPool("u1");

    assertThat(ip).isEqualTo("fd00:1::10");
    assertThat(alice.getStaticIpv6()).isEqualTo("fd00:1::10");
    verify(userRepository).save(alice);
  }

  @Test
  void allocateIpv6FromGroupPoolSkipsUsedAddresses() {
    User alice = user("u1", "alice", null);
    User bob = user("u2", "bob", null);
    bob.setStaticIpv6("fd00:1::10");
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(userRepository.findAll()).thenReturn(List.of(alice, bob));
    dualStack();
    poolFor6("fd00:1::10-fd00:1::11");

    String ip = service.allocateIpv6FromGroupPool("u1");

    assertThat(ip).isEqualTo("fd00:1::11");
  }

  @Test
  void allocateIpv6FromGroupPoolThrowsWhenNoPool() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    dualStack();
    poolFor6((String) null);

    assertThatThrownBy(() -> service.allocateIpv6FromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("no_ipv6_pool"));
  }

  @Test
  void allocateIpv6FromGroupPoolThrowsWhenPoolExhausted() {
    User alice = user("u1", "alice", null);
    User bob = user("u2", "bob", null);
    bob.setStaticIpv6("fd00:1::10");
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(userRepository.findAll()).thenReturn(List.of(alice, bob));
    dualStack();
    poolFor6("fd00:1::10-fd00:1::10");

    assertThatThrownBy(() -> service.allocateIpv6FromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("pool_exhausted"));
  }

  @Test
  void allocateIpv6FromGroupPoolThrowsWhenPoolMalformed() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    dualStack();
    poolFor6("not-a-pool");

    assertThatThrownBy(() -> service.allocateIpv6FromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ipv6_pool"));
  }

  @Test
  void allocateIpv6FromGroupPoolThrowsWhenStartGreaterThanEnd() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    dualStack();
    poolFor6("fd00:1::11-fd00:1::10");

    assertThatThrownBy(() -> service.allocateIpv6FromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ipv6_pool"));
  }

  @Test
  void allocateIpv6FromGroupPoolThrowsWhenPoolStartsAtNetworkOrServer() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    dualStack();
    poolFor6("fd00:1::-fd00:1::10");

    assertThatThrownBy(() -> service.allocateIpv6FromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ipv6_pool"));
  }

  @Test
  void allocateIpv6FromGroupPoolThrowsWhenPoolRangeTooLarge() {
    User alice = user("u1", "alice", null);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    dualStack();
    poolFor6("fd00:1::2-fd00:1::1:2");

    assertThatThrownBy(() -> service.allocateIpv6FromGroupPool("u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ipv6_pool"));
  }

  @Test
  void groupIpv6PoolForReturnsMostSpecificGroupPool() {
    when(settingsService.groupChainForUser("u1")).thenReturn(List.of("group1", "group2"));
    when(settingsService.groupSettings("group1")).thenReturn(Map.of());
    when(settingsService.groupSettings("group2"))
        .thenReturn(Map.of(SettingKeys.STATIC_IPV6_POOL, "fd00:1::10-fd00:1::ff"));

    assertThat(service.groupIpv6PoolFor("u1")).isEqualTo("fd00:1::10-fd00:1::ff");
  }

  @Test
  void groupIpv6PoolForReturnsNullWithoutPools() {
    when(settingsService.groupChainForUser("u1")).thenReturn(List.of("group1"));
    when(settingsService.groupSettings("group1")).thenReturn(Map.of());

    assertThat(service.groupIpv6PoolFor("u1")).isNull();
  }

  @Test
  void validateIpv6PoolAcceptsBlank() {
    service.validateIpv6Pool(null);
    service.validateIpv6Pool("  ");
  }

  @Test
  void validateIpv6PoolThrowsWhenIpv6Disabled() {
    assertThatThrownBy(() -> service.validateIpv6Pool("fd00:1::10-fd00:1::ff"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ipv6_disabled"));
  }

  @Test
  void validateIpv6PoolRejectsMalformed() {
    dualStack();
    assertThatThrownBy(() -> service.validateIpv6Pool("not-a-pool"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ipv6_pool"));
  }

  @Test
  void validateIpv6PoolRejectsStartAtNetworkOrServer() {
    dualStack();
    assertThatThrownBy(() -> service.validateIpv6Pool("fd00:1::-fd00:1::10"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ipv6_pool"));
    assertThatThrownBy(() -> service.validateIpv6Pool("fd00:1::1-fd00:1::10"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ipv6_pool"));
  }

  @Test
  void validateIpv6PoolRejectsStartGreaterThanEnd() {
    dualStack();
    assertThatThrownBy(() -> service.validateIpv6Pool("fd00:1::11-fd00:1::10"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ipv6_pool"));
  }

  @Test
  void syncAllRemovesStaleCcdFilesAndWritesCurrent() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    when(userRepository.findAll()).thenReturn(List.of(alice));
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("/tmp/opnl-ccd-test"));
    java.nio.file.Files.write(ccdPath("ghost"), List.of("stale"));

    service.syncAll();

    assertThat(ccdPath("ghost")).doesNotExist();
    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).contains("ifconfig-push 10.8.0.100 255.255.255.0");
  }

  @Test
  void syncAllToleratesMissingCcdDir() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    when(userRepository.findAll()).thenReturn(List.of(alice));
    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("/tmp/opnl-ccd-test"));

    service.syncAll();

    assertThat(ccdPath("alice")).exists();
  }

  @Test
  void deleteCcdRemovesFile() throws Exception {
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("/tmp/opnl-ccd-test"));
    java.nio.file.Files.write(ccdPath("alice"), List.of("line"));

    service.deleteCcd("alice");

    assertThat(ccdPath("alice")).doesNotExist();
  }

  @Test
  void deleteCcdIgnoresBlankName() {
    service.deleteCcd(" ");
    service.deleteCcd(null);
  }

  @Test
  void writeUserCcdIpv6EnabledAddsIpv6Pushes() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    alice.setStaticIpv6("fd00:1::10");
    dualStack();
    effectiveFor(Map.of(SettingKeys.TUNNEL_MODE, "full"));

    service.writeUserCcd(alice);

    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines)
        .contains(
            "ifconfig-ipv6-push fd00:1::10/64 fd00:1:0:0:0:0:0:1",
            "push \"redirect-gateway ipv6\"");
  }

  @Test
  void writeUserCcdOmitsIpv6LinesWhenDisabled() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    alice.setStaticIpv6("fd00:1::10");

    service.writeUserCcd(alice);

    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    assertThat(lines).doesNotContain("ifconfig-ipv6-push");
  }

  @Test
  void writeUserCcdAppendsDnsServersAndDomain() throws Exception {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    effectiveFor(
        Map.of(
            SettingKeys.TUNNEL_MODE,
            "split",
            SettingKeys.DNS_SERVERS,
            "1.1.1.1, 8.8.8.8",
            SettingKeys.DNS_DOMAIN,
            "vpn.local"));

    service.writeUserCcd(alice);

    List<String> lines = java.nio.file.Files.readAllLines(ccdPath("alice"));
    // DNS option v2: scoped resolvers for macOS/iOS split-DNS fix.
    assertThat(lines)
        .contains(
            "push \"dns server 0 address 1.1.1.1\"",
            "push \"dns server 1 address 8.8.8.8\"",
            "push \"dns server 0 resolve-domains .vpn.local\"",
            "push \"dns search-domains vpn.local\"",
            "push \"dhcp-option DOMAIN vpn.local\"");
    assertThat(lines).doesNotContain("push \"dhcp-option DNS");
  }

  @Test
  void writeUserCcdThrowsWhenCcdDirCannotBeCreated() {
    User alice = savedUser("u1", "alice", "10.8.0.100");
    PassageProperties properties = mock(PassageProperties.class);
    PassageProperties.OpenVpn openvpn = mock(PassageProperties.OpenVpn.class);
    when(properties.openvpn()).thenReturn(openvpn);
    when(openvpn.ccdDir()).thenReturn("/dev/null/ccd");
    CcdService broken =
        new CcdService(
            userRepository,
            settingsService,
            serverSettingRepository,
            serverConfigGenerator,
            properties);

    assertThatThrownBy(() -> broken.writeUserCcd(alice))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ccd_write_failed"));
  }
}
