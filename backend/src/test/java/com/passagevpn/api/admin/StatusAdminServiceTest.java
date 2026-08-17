package com.passagevpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.passagevpn.config.PassageProperties;
import com.passagevpn.monitor.MgmtClientManager;
import com.passagevpn.monitor.MgmtStatus;
import com.passagevpn.network.ConnectionRegistry;
import com.passagevpn.network.Daemon;
import com.passagevpn.network.DaemonService;
import com.passagevpn.network.ServerConfig.Protocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusAdminServiceTest {

  @TempDir Path tempDir;

  private DaemonService daemonService;
  private ConnectionRegistry connectionRegistry;
  private StatusAdminService service;

  @BeforeEach
  void setUp() {
    daemonService = mock(DaemonService.class);
    connectionRegistry = new ConnectionRegistry();
  }

  private StatusAdminService serviceWith(MgmtClientManager clientManager) {
    PassageProperties properties =
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
    return new StatusAdminService(daemonService, connectionRegistry, clientManager, properties);
  }

  private Daemon daemon(int index, boolean enabled) {
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
        .enabled(enabled)
        .createdAt(Instant.now())
        .build();
  }

  @Test
  void statusReportsConfigPresenceAndMgmtReachability() throws IOException {
    MgmtClientManager clientManager = mock(MgmtClientManager.class);
    // Fresh poll for daemon 0, never reached for daemon 1.
    when(clientManager.cachedStatus(null, 0))
        .thenReturn(new MgmtStatus(Instant.now(), "OpenVPN 2.6.20 [DCO]", 0, List.of(), true));
    when(clientManager.cachedStatus(null, 1)).thenReturn(null);
    service = serviceWith(clientManager);

    Files.createDirectories(tempDir.resolve("config"));
    Files.writeString(tempDir.resolve("config").resolve("daemon-0.conf"), "port 1194\n");
    when(daemonService.list()).thenReturn(List.of(daemon(0, true), daemon(1, true)));

    ServerStatusDto status = service.status();

    assertThat(status.brand()).isEqualTo("OpenVPN Panel");
    assertThat(status.activeConnections()).isZero();
    assertThat(status.daemons()).hasSize(2);

    ServerStatusDto.DaemonStatus first = status.daemons().get(0);
    assertThat(first.index()).isZero();
    assertThat(first.configPresent()).isTrue();
    assertThat(first.mgmtReachable()).isTrue();
    assertThat(first.dco()).isTrue();

    ServerStatusDto.DaemonStatus second = status.daemons().get(1);
    assertThat(second.configPresent()).isFalse();
    assertThat(second.mgmtReachable()).isFalse();
  }

  @Test
  void statusTreatsStaleManagementStatusAsUnreachable() {
    MgmtClientManager clientManager = mock(MgmtClientManager.class);
    // Last successful poll long before the freshness window.
    when(clientManager.cachedStatus(null, 0))
        .thenReturn(
            new MgmtStatus(Instant.now().minusSeconds(600), "OpenVPN 2.6.20", 0, List.of(), false));
    service = serviceWith(clientManager);

    when(daemonService.list()).thenReturn(List.of(daemon(0, true)));

    assertThat(service.status().daemons().get(0).mgmtReachable()).isFalse();
  }

  @Test
  void statusCountsActiveConnections() {
    service = serviceWith(mock(MgmtClientManager.class));
    when(daemonService.list()).thenReturn(List.of(daemon(0, true)));
    connectionRegistry.register("alice", "alice", "10.8.0.2", null, "203.0.113.5", "daemon-0");
    connectionRegistry.register("bob", "bob", "10.8.0.3", null, "203.0.113.6", "daemon-0");
    ServerStatusDto status = service.status();

    assertThat(status.activeConnections()).isEqualTo(2);
  }
}
