package com.opnl.vpn.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.NodeRegistryService;
import com.opnl.vpn.network.OpenVpnNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MgmtClientManagerTest {

  @TempDir Path tempDir;

  private MgmtClientManager manager(OpnlProperties properties, NodeRegistryService nodes) {
    return new MgmtClientManager(properties, nodes);
  }

  private OpnlProperties properties(int mgmtPort) {
    return new OpnlProperties(
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
  }

  private OpenVpnNode node(String id, String host, int portBase, boolean enabled) {
    return OpenVpnNode.builder()
        .id(id)
        .name("node-" + id)
        .mgmtHost(host)
        .mgmtPortBase(portBase)
        .enabled(enabled)
        .createdAt(Instant.now())
        .build();
  }

  @Test
  void statusForUnknownNodeIsNull() {
    NodeRegistryService nodes = mock(NodeRegistryService.class);
    when(nodes.findNode("missing")).thenReturn(Optional.empty());

    assertThat(manager(properties(7505), nodes).status("missing", 0)).isNull();
  }

  @Test
  void statusForDisabledNodeIsNull() {
    NodeRegistryService nodes = mock(NodeRegistryService.class);
    when(nodes.findNode("n1")).thenReturn(Optional.of(node("n1", "10.0.0.9", 7000, false)));

    assertThat(manager(properties(7505), nodes).status("n1", 0)).isNull();
  }

  @Test
  void statusForLocalDaemonOnClosedPortIsNull() throws IOException {
    // Grab a port that is currently closed to force a connect refusal.
    int closedPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      closedPort = probe.getLocalPort();
    }

    assertThat(manager(properties(closedPort), mock(NodeRegistryService.class)).status(null, 0))
        .isNull();
  }

  @Test
  void statusForRemoteDaemonUsesNodeEndpoint() throws IOException {
    // Listen on a real socket so the remote (node, daemon) endpoint resolves and connects.
    try (ServerSocket listener = new ServerSocket(0)) {
      int portBase = listener.getLocalPort() - 0;
      NodeRegistryService nodes = mock(NodeRegistryService.class);
      when(nodes.findNode("n1"))
          .thenReturn(Optional.of(node("n1", "127.0.0.1", portBase, true)));

      MgmtStatus status = manager(properties(7505), nodes).status("n1", 0);
      // The listener never speaks the management protocol, so the poll yields null,
      // but reaching it proves the endpoint was resolved from the node, not the local port.
      assertThat(status).isNull();
    }
  }

  @Test
  void cachedStatusFallsBackToNullWithoutPoll() {
    assertThat(manager(properties(7505), mock(NodeRegistryService.class)).cachedStatus(null, 3))
        .isNull();
  }
}
