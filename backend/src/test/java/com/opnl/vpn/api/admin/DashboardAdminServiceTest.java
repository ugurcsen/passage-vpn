package com.opnl.vpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.monitor.MgmtStatus.MgmtClientStatus;
import com.opnl.vpn.monitor.TrafficAggregator;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.ServerConfig.Protocol;
import com.opnl.vpn.pki.CertificateRepository;
import com.opnl.vpn.user.UserRepository;
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
  private DashboardAdminService service;

  @BeforeEach
  void setUp() {
    OpnlProperties properties =
        new OpnlProperties(
            tempDir.resolve("data").toString(),
            "OpenVPN Panel",
            "internal-token",
            new OpnlProperties.Jwt("j".repeat(64), 900, 14),
            new OpnlProperties.Auth("local", 5, 300, 300, 20, 60),
            new OpnlProperties.OpenVpn(
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
                tempDir.resolve("logs").toString()));
    registry = new ConnectionRegistry();
    aggregator = new TrafficAggregator();
    DaemonService daemonService = mock(DaemonService.class);
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
    registry.register("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    aggregator.update(
        List.of(new MgmtClientStatus("alice", "203.0.113.5", "10.8.0.2", 4096, 2048, t0, 1)), t0);

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
    registry.register("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");

    DashboardDto dashboard = service.dashboard();

    assertThat(dashboard.activeConnections()).isEqualTo(1);
    assertThat(dashboard.recentConnections()).hasSize(1);
    assertThat(dashboard.recentConnections().get(0).bytesIn()).isNull();
    assertThat(dashboard.recentConnections().get(0).bytesOut()).isNull();
  }
}
