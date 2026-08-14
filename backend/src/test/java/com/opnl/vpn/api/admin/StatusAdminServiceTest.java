package com.opnl.vpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.monitor.MgmtClientManager;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.NodeRegistryService;
import com.opnl.vpn.network.ServerConfig.Protocol;
import java.io.IOException;
import java.net.ServerSocket;
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

  private StatusAdminService serviceWith(int mgmtPort) {
    OpnlProperties properties =
        new OpnlProperties(
            tempDir.resolve("data").toString(),
            "OpenVPN Panel",
            "internal-token",
            new OpnlProperties.Jwt("j".repeat(64), 900, 14),
            new OpnlProperties.Auth("local", 5, 300, 300, 20, 60),
            new OpnlProperties.OpenVpn(
                "127.0.0.1",
                mgmtPort,
                "vpn.example.com",
                tempDir.resolve("pki").toString(),
                tempDir.resolve("ccd").toString(),
                tempDir.resolve("config").toString(),
                tempDir.resolve("scripts").toString(),
                "openvpn/scripts",
                "http://backend:8080",
                "easyrsa",
                tempDir.resolve("logs").toString()));
    return new StatusAdminService(
        daemonService,
        connectionRegistry,
        new MgmtClientManager(properties, mock(NodeRegistryService.class)),
        mock(NodeRegistryService.class),
        properties);
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
    // Bind a real listener; daemon index 0 probes exactly this port.
    try (ServerSocket listener = new ServerSocket(0)) {
      int mgmtPort = listener.getLocalPort();
      service = serviceWith(mgmtPort);
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

      ServerStatusDto.DaemonStatus second = status.daemons().get(1);
      assertThat(second.configPresent()).isFalse();
      assertThat(second.mgmtReachable()).isFalse();
    }
  }

  @Test
  void statusCountsActiveConnections() {
    service = serviceWith(17505);
    when(daemonService.list()).thenReturn(List.of(daemon(0, true)));
    connectionRegistry.register("alice", "alice", "10.8.0.2", null, "203.0.113.5", "daemon-0");
    connectionRegistry.register("bob", "bob", "10.8.0.3", null, "203.0.113.6", "daemon-0");
    ServerStatusDto status = service.status();

    assertThat(status.activeConnections()).isEqualTo(2);
  }
}
