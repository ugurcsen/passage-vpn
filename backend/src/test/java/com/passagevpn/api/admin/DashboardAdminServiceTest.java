package com.passagevpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.config.PassageProperties;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.monitor.MgmtStatus.MgmtClientStatus;
import com.passagevpn.monitor.TrafficAggregator;
import com.passagevpn.network.ConnectionRegistry;
import com.passagevpn.network.Daemon;
import com.passagevpn.network.DaemonService;
import com.passagevpn.network.ServerConfig.Protocol;
import com.passagevpn.pki.Certificate;
import com.passagevpn.pki.CertificateRepository;
import com.passagevpn.user.UserRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardAdminServiceTest {

  @TempDir Path tempDir;

  private ConnectionRegistry registry;
  private TrafficAggregator aggregator;
  private PassageProperties properties;
  private DaemonService daemonService;
  private DashboardAdminService service;

  @BeforeEach
  void setUp() {
    properties =
        new PassageProperties(
            tempDir.resolve("data").toString(),
            "OpenVPN Panel",
            "internal-token",
            new PassageProperties.Jwt("j".repeat(64), 900, 14),
            new PassageProperties.Auth("local", 5, 300, 300, 20, 60),
            new PassageProperties.OpenVpn(
                "127.0.0.1",
                17505,
                "vpn.example.com",
                tempDir.resolve("pki").toString(),
                tempDir.resolve("ccd").toString(),
                tempDir.resolve("config").toString(),
                tempDir.resolve("scripts").toString(),
                "openvpn/scripts",
                "http://backend:8080",
                "easyrsa",
                tempDir.resolve("logs").toString(),
                "mgmt-pass",
                730,
                1194,
                1194,
                1195,
                1195));
    registry = new ConnectionRegistry();
    aggregator = new TrafficAggregator();
    daemonService = mock(DaemonService.class);
    when(daemonService.list())
        .thenReturn(
            List.of(
                Daemon.builder()
                    .id("d0")
                    .daemonIndex(0)
                    .name("Primary")
                    .port(1194)
                    .proto(Protocol.udp)
                    .subnet("10.8.0.0")
                    .subnetMask("255.255.255.0")
                    .dnsServers(List.of())
                    .extraRoutes(List.of())
                    .fullTunnel(true)
                    .clientCertNotRequired(false)
                    .authUserPass(true)
                    .enabled(true)
                    .createdAt(Instant.now())
                    .build()));
    service =
        new DashboardAdminService(
            mock(UserRepository.class),
            mock(GroupRepository.class),
            mock(CertificateRepository.class),
            registry,
            daemonService,
            aggregator,
            properties);
  }

  @Test
  void dashboardRecentConnectionsCarryTrafficCounters() {
    registry.register("alice", "alice", "10.8.0.2", null, "203.0.113.5", "daemon-0");
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    aggregator.update(
        List.of(new MgmtClientStatus("alice", "203.0.113.5", "10.8.0.2", null, 4096, 2048, t0, 1)),
        t0);

    DashboardDto dashboard = service.dashboard();

    assertThat(dashboard.activeConnections()).isEqualTo(1);
    assertThat(dashboard.recentConnections()).hasSize(1);
    ConnectionDto connection = dashboard.recentConnections().get(0);
    assertThat(connection.commonName()).isEqualTo("alice");
    assertThat(connection.bytesIn()).isEqualTo(4096);
    assertThat(connection.bytesOut()).isEqualTo(2048);
  }

  @Test
  void dashboardRecentConnectionsFallBackToNullTrafficWhenAggregatorHasNoData() {
    registry.register("alice", "alice", "10.8.0.2", null, "203.0.113.5", "daemon-0");
    DashboardDto dashboard = service.dashboard();

    assertThat(dashboard.activeConnections()).isEqualTo(1);
    assertThat(dashboard.recentConnections()).hasSize(1);
    assertThat(dashboard.recentConnections().get(0).bytesIn()).isNull();
    assertThat(dashboard.recentConnections().get(0).bytesOut()).isNull();
  }

  @Test
  void dbCountsAreCachedWithinTtl() {
    UserRepository userRepository = mock(UserRepository.class);
    GroupRepository groupRepository = mock(GroupRepository.class);
    CertificateRepository certificateRepository = mock(CertificateRepository.class);
    when(userRepository.count()).thenReturn(3L);
    when(groupRepository.count()).thenReturn(2L);
    when(certificateRepository.countByStatus(Certificate.Status.VALID)).thenReturn(5L);
    DashboardAdminService svc =
        new DashboardAdminService(
            userRepository,
            groupRepository,
            certificateRepository,
            registry,
            daemonService,
            aggregator,
            properties);

    DashboardDto first = svc.dashboard();
    DashboardDto second = svc.dashboard();

    assertThat(first.users()).isEqualTo(3);
    assertThat(second.users()).isEqualTo(3);
    verify(userRepository, times(1)).count();
    verify(groupRepository, times(1)).count();
    verify(certificateRepository, times(1)).countByStatus(Certificate.Status.VALID);
  }
}
