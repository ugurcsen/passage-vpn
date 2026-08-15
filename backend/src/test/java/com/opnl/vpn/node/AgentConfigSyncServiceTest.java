package com.opnl.vpn.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.node.NodeConfigBundleService.FileEntry;
import com.opnl.vpn.node.NodeConfigBundleService.NodeConfigBundle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the agent config pull: atomic writes, permissions, stale cleanup and version skip. */
class AgentConfigSyncServiceTest {

  @TempDir Path tempDir;

  private AgentRegistrationService registration;
  private ObjectMapper objectMapper;
  private AgentConfigSyncService service;
  private NodeConfigBundle currentBundle;

  @BeforeEach
  void setUp() {
    registration = mock(AgentRegistrationService.class);
    when(registration.currentNodeId()).thenReturn("n-1");
    objectMapper = new ObjectMapper();
    OpnlProperties props =
        new OpnlProperties(
            tempDir.toString(),
            "OpenVPN Panel",
            "token",
            null,
            null,
            new OpnlProperties.OpenVpn(
                "127.0.0.1",
                7505,
                "vpn.example.com",
                tempDir.resolve("pki").toString(),
                tempDir.resolve("ccd").toString(),
                tempDir.resolve("config").toString(),
                tempDir.resolve("config/scripts").toString(),
                tempDir.resolve("src").toString(),
                "http://localhost",
                "easyrsa",
                tempDir.resolve("logs").toString(),
                "mgmt-secret",
                730));
    service = new AgentConfigSyncService(registration, props, objectMapper);
  }

  private NodeConfigBundle sampleBundle(String version) {
    return new NodeConfigBundle(
        version,
        List.of(
            new FileEntry("daemon-0.conf", "port 1194\n"),
            new FileEntry("daemon-0.mgmt-pass", "mgmt-secret")),
        List.of(new FileEntry("ca.crt", "CA"), new FileEntry("server.key", "KEY")),
        List.of(new FileEntry("alice", "ifconfig-push 10.8.0.10 255.255.255.0")),
        List.of(new FileEntry("verify-user-pass.sh", "#!/bin/sh")),
        List.of(new FileEntry("opnl-domains.conf", "address=/x/1.2.3.4")));
  }

  private void serveCurrentBundle() throws Exception {
    NodeConfigBundle bundle = currentBundle;
    when(registration.postJson("/internal/node/config", "{\"nodeId\":\"n-1\"}"))
        .thenReturn(objectMapper.writeValueAsString(bundle));
  }

  private String read(Path file) throws Exception {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  @Test
  void appliesBundleToGatewayDirectories() throws Exception {
    currentBundle = sampleBundle("v1");
    serveCurrentBundle();

    service.tick();

    assertThat(read(tempDir.resolve("config/daemon-0.conf"))).isEqualTo("port 1194\n");
    assertThat(read(tempDir.resolve("config/daemon-0.mgmt-pass"))).isEqualTo("mgmt-secret");
    assertThat(read(tempDir.resolve("pki/ca.crt"))).isEqualTo("CA");
    assertThat(read(tempDir.resolve("pki/server.key"))).isEqualTo("KEY");
    assertThat(read(tempDir.resolve("ccd/alice")))
        .isEqualTo("ifconfig-push 10.8.0.10 255.255.255.0");
    assertThat(read(tempDir.resolve("config/scripts/verify-user-pass.sh"))).isEqualTo("#!/bin/sh");
    assertThat(read(tempDir.resolve("config/dnsmasq.d/opnl-domains.conf")))
        .isEqualTo("address=/x/1.2.3.4");
  }

  @Test
  void sameVersionSkipsRewrite() throws Exception {
    currentBundle = sampleBundle("v1");
    serveCurrentBundle();
    service.tick();

    Files.delete(tempDir.resolve("pki/ca.crt"));
    service.tick();

    assertThat(tempDir.resolve("pki/ca.crt")).doesNotExist();
  }

  @Test
  void removesStaleManagedFilesButKeepsUnmanagedOnes() throws Exception {
    Files.createDirectories(tempDir.resolve("config"));
    Files.writeString(tempDir.resolve("config/daemon-9.conf"), "stale");
    Files.writeString(tempDir.resolve("config/keepme.txt"), "unmanaged");
    Files.createDirectories(tempDir.resolve("ccd"));
    Files.writeString(tempDir.resolve("ccd/olduser"), "stale");

    currentBundle = sampleBundle("v1");
    serveCurrentBundle();

    service.tick();

    assertThat(tempDir.resolve("config/daemon-9.conf")).doesNotExist();
    assertThat(tempDir.resolve("ccd/olduser")).doesNotExist();
    assertThat(tempDir.resolve("config/keepme.txt")).exists();
  }

  @Test
  void sensitiveFilesAreOwnerReadWriteOnly() throws Exception {
    currentBundle = sampleBundle("v1");
    serveCurrentBundle();

    service.tick();

    Set<PosixFilePermission> pass =
        Files.getPosixFilePermissions(tempDir.resolve("config/daemon-0.mgmt-pass"));
    assertThat(pass)
        .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Set<PosixFilePermission> key = Files.getPosixFilePermissions(tempDir.resolve("pki/server.key"));
    assertThat(key)
        .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
  }

  @Test
  void scriptsAreMarkedExecutable() throws Exception {
    currentBundle = sampleBundle("v1");
    serveCurrentBundle();

    service.tick();

    assertThat(Files.isExecutable(tempDir.resolve("config/scripts/verify-user-pass.sh"))).isTrue();
  }

  @Test
  void pathTraversalNameNeverLeavesTargetDirectory() throws Exception {
    currentBundle =
        new NodeConfigBundle(
            "v1",
            List.of(),
            List.of(new FileEntry("../../escape.key", "KEY")),
            List.of(),
            List.of(),
            List.of());
    serveCurrentBundle();

    service.tick();

    assertThat(tempDir.getParent().resolve("escape.key")).doesNotExist();
  }

  @Test
  void skipsWhenNotRegisteredYet() throws Exception {
    when(registration.currentNodeId()).thenReturn(null);
    service.tick();
    assertThat(tempDir.resolve("config")).doesNotExist();
  }
}
