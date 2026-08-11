package com.opnl.vpn.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.api.admin.ConnectionDto;
import com.opnl.vpn.api.admin.MonitorSnapshotDto;
import com.opnl.vpn.api.admin.ServerStatusDto.DaemonStatus;
import com.opnl.vpn.api.admin.SystemInfoDto;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.monitor.MgmtStatus.MgmtClientStatus;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.ServerConfig.Protocol;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorServiceTest {

  @TempDir Path tempDir;

  private ConnectionRegistry registry;
  private DaemonService daemonService;
  private OpnlProperties properties;
  private MgmtClientManager clientManager;
  private ConnectionLogService connectionLogService;
  private MonitorService service;

  @BeforeEach
  void setUp() {
    properties =
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
    daemonService = mock(DaemonService.class);
    when(daemonService.list()).thenReturn(List.of(daemon(0), daemon(1)));
    clientManager = mock(MgmtClientManager.class);
    connectionLogService = mock(ConnectionLogService.class);
    SystemInfoService systemInfoService = mock(SystemInfoService.class);
    when(systemInfoService.systemInfo())
        .thenReturn(new SystemInfoDto(25.0, 1_000_000, 400_000, 2_000_000, 800_000, 4));
    service =
        new MonitorService(
            clientManager,
            new TrafficAggregator(),
            registry,
            daemonService,
            mock(MonitorBroadcaster.class),
            systemInfoService,
            properties,
            connectionLogService,
            new ObjectMapper());
  }

  private Daemon daemon(int index) {
    return Daemon.builder()
        .id("d" + index)
        .daemonIndex(index)
        .name("Daemon " + index)
        .port(1194 + index)
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
        .build();
  }

  @Test
  void currentSnapshotMergesRegistryConnectionsWithTraffic() {
    registry.register("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    TrafficAggregator aggregator = new TrafficAggregator();
    aggregator.update(
        List.of(new MgmtClientStatus("alice", "203.0.113.5", "10.8.0.2", 4096, 2048, t0, 1)), t0);

    MonitorService withTraffic =
        new MonitorService(
            clientManager,
            aggregator,
            registry,
            daemonService,
            mock(MonitorBroadcaster.class),
            mock(SystemInfoService.class),
            properties,
            connectionLogService,
            new ObjectMapper());

    MonitorSnapshotDto snapshot = withTraffic.currentSnapshot();

    assertThat(snapshot.connections()).hasSize(1);
    ConnectionDto connection = snapshot.connections().get(0);
    assertThat(connection.bytesIn()).isEqualTo(4096);
    assertThat(connection.bytesOut()).isEqualTo(2048);
    assertThat(snapshot.activeConnections()).isEqualTo(1);
    assertThat(snapshot.daemons()).hasSize(2);
  }

  @Test
  void currentSnapshotReportsUnreachableDaemons() {
    MonitorSnapshotDto snapshot = service.currentSnapshot();
    DaemonStatus daemon = snapshot.daemons().get(0);
    assertThat(daemon.index()).isZero();
    assertThat(daemon.mgmtReachable()).isFalse();
    assertThat(daemon.dco()).isNull();
    assertThat(snapshot.system()).isNotNull();
    assertThat(snapshot.system().cpuLoadPercent()).isEqualTo(25.0);
  }

  @Test
  void pollDoesNotThrowWhenManagementUnreachable() {
    service.poll();
    assertThat(service.latestSnapshot()).isNotNull();
    assertThat(service.latestSnapshot().at()).isNotNull();
  }

  @Test
  void pollReconcilesOpenSessionsAgainstCompleteLiveView() {
    when(clientManager.status(0))
        .thenReturn(
            new MgmtStatus(
                Instant.now(),
                "OpenVPN 2.6.20",
                1,
                List.of(
                    new MgmtClientStatus(
                        "alice", "203.0.113.5", "10.8.0.2", 1, 1, Instant.now(), 1)),
                false));
    when(clientManager.status(1))
        .thenReturn(
            new MgmtStatus(
                Instant.now(),
                "OpenVPN 2.6.20",
                1,
                List.of(
                    new MgmtClientStatus("bob", "203.0.113.9", "10.8.0.3", 1, 1, Instant.now(), 2)),
                false));

    service.poll();

    verify(connectionLogService).reconcileOpenSessions(Set.of("alice", "bob"));
  }

  @Test
  void pollReconcilesEvenWhenNoClientIsConnected() {
    when(clientManager.status(0))
        .thenReturn(new MgmtStatus(Instant.now(), "OpenVPN 2.6.20", 0, List.of(), false));
    when(clientManager.status(1))
        .thenReturn(new MgmtStatus(Instant.now(), "OpenVPN 2.6.20", 0, List.of(), false));

    service.poll();

    // Empty live view is valid (no clients) and must still close stale rows.
    verify(connectionLogService).reconcileOpenSessions(Set.of());
  }

  @Test
  void pollSkipsReconciliationWhenAnyDaemonIsUnreachable() {
    when(clientManager.status(0)).thenReturn(null);
    when(clientManager.status(1))
        .thenReturn(
            new MgmtStatus(
                Instant.now(),
                "OpenVPN 2.6.20",
                1,
                List.of(
                    new MgmtClientStatus(
                        "alice", "203.0.113.5", "10.8.0.2", 1, 1, Instant.now(), 1)),
                false));

    service.poll();

    verify(connectionLogService, never()).reconcileOpenSessions(anySet());
  }

  @Test
  void pollDropsStaleRegistrySessionsNotInLiveView() {
    registry.register("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");
    registry.register("carol", "carol", "10.8.0.4", "203.0.113.7", "daemon-1");
    when(clientManager.status(0))
        .thenReturn(
            new MgmtStatus(
                Instant.now(),
                "OpenVPN 2.6.20",
                1,
                List.of(
                    new MgmtClientStatus(
                        "alice", "203.0.113.5", "10.8.0.2", 1, 1, Instant.now(), 1)),
                false));
    when(clientManager.status(1))
        .thenReturn(new MgmtStatus(Instant.now(), "OpenVPN 2.6.20", 0, List.of(), false));

    service.poll();

    // carol is registered in-memory but missing from the live view -> dropped.
    assertThat(registry.sessions()).hasSize(1);
    assertThat(registry.sessions().get(0).commonName()).isEqualTo("alice");
  }
}
