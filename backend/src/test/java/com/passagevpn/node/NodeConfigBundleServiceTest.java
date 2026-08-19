package com.passagevpn.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.network.ConfigWriter;
import com.passagevpn.network.Daemon;
import com.passagevpn.network.DaemonService;
import com.passagevpn.network.ServerConfig;
import com.passagevpn.network.ServerConfigGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the node config bundle assembly: daemon renders, PKI/CCD/scripts/dnsmasq + versioning.
 */
class NodeConfigBundleServiceTest {

  @TempDir Path tempDir;

  private DaemonService daemonService;
  private PassageProperties props;
  private NodeConfigBundleService service;

  @BeforeEach
  void setUp() throws Exception {
    daemonService = mock(DaemonService.class);
    props =
        new PassageProperties(
            tempDir.toString(),
            "OpenVPN Panel",
            "token",
            null,
            null,
            null,
            new PassageProperties.OpenVpn(
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
                730,
                1194,
                1194,
                1195,
                1195));
    service =
        new NodeConfigBundleService(
            daemonService,
            new ConfigWriter(props),
            new ServerConfigGenerator(new ObjectMapper()),
            props);

    Files.createDirectories(tempDir.resolve("pki"));
    Files.createDirectories(tempDir.resolve("ccd"));
    Files.createDirectories(tempDir.resolve("config/scripts"));
    Files.createDirectories(tempDir.resolve("config/dnsmasq.d"));
    Files.writeString(tempDir.resolve("pki/ca.crt"), "CA-CERT");
    Files.writeString(tempDir.resolve("pki/server.key"), "SERVER-KEY");
    Files.writeString(tempDir.resolve("pki/crl.pem"), "CRL-V2");
    Files.writeString(tempDir.resolve("ccd/alice"), "ifconfig-push 10.8.0.10 255.255.255.0");
    Files.writeString(tempDir.resolve("config/scripts/verify-user-pass.sh"), "#!/bin/sh");
    Files.writeString(
        tempDir.resolve("config/dnsmasq.d/passage-domains.conf"), "address=/x/1.2.3.4");
  }

  private Daemon daemon(int index) {
    return Daemon.builder()
        .id("d" + index)
        .daemonIndex(index)
        .port(1194 + index)
        .proto(com.passagevpn.network.ServerConfig.Protocol.udp)
        .subnet("10.8.0.0")
        .subnetMask("255.255.255.0")
        .enabled(true)
        .build();
  }

  @Test
  void bundleIncludesOnlyNodeDaemonsAndGatewayFiles() {
    when(daemonService.enabledDaemonsForNode("n-1")).thenReturn(List.of(daemon(0)));
    when(daemonService.toServerConfig(any())).thenReturn(ServerConfig.defaults());
    when(daemonService.networkMode()).thenReturn("nat");

    NodeConfigBundleService.NodeConfigBundle bundle = service.bundleForNode("n-1");

    assertThat(bundle.daemons())
        .extracting(NodeConfigBundleService.FileEntry::name)
        .containsExactlyInAnyOrder("daemon-0.conf", "daemon-0.mgmt-pass");
    assertThat(bundle.pki())
        .extracting(NodeConfigBundleService.FileEntry::name)
        .containsExactlyInAnyOrder("ca.crt", "server.key", "crl.pem");
    assertThat(bundle.ccd()).extracting(NodeConfigBundleService.FileEntry::name).contains("alice");
    assertThat(bundle.scripts())
        .extracting(NodeConfigBundleService.FileEntry::name)
        .contains("verify-user-pass.sh");
    assertThat(bundle.dnsmasq())
        .extracting(NodeConfigBundleService.FileEntry::name)
        .contains("passage-domains.conf");
    assertThat(bundle.version()).hasSize(64);
    assertThat(
            bundle.pki().stream()
                .filter(e -> e.name().equals("crl.pem"))
                .map(NodeConfigBundleService.FileEntry::content)
                .findFirst())
        .contains("CRL-V2");
  }

  @Test
  void versionStableAcrossCallsAndChangesOnContentChange() throws Exception {
    when(daemonService.enabledDaemonsForNode("n-1")).thenReturn(List.of(daemon(0)));
    when(daemonService.toServerConfig(any())).thenReturn(ServerConfig.defaults());
    when(daemonService.networkMode()).thenReturn("nat");

    String first = service.bundleForNode("n-1").version();
    String second = service.bundleForNode("n-1").version();
    assertThat(second).isEqualTo(first);

    Files.writeString(tempDir.resolve("pki/crl.pem"), "CRL-NEWER");
    String third = service.bundleForNode("n-1").version();
    assertThat(third).isNotEqualTo(first);
  }

  @Test
  void bundleForNodeWithoutDaemonsIsEmptyButVersioned() {
    when(daemonService.enabledDaemonsForNode("n-1")).thenReturn(List.of());
    when(daemonService.toServerConfig(any())).thenReturn(ServerConfig.defaults());
    when(daemonService.networkMode()).thenReturn("nat");

    NodeConfigBundleService.NodeConfigBundle bundle = service.bundleForNode("n-1");

    assertThat(bundle.daemons()).isEmpty();
    assertThat(bundle.pki()).isNotEmpty();
    assertThat(bundle.version()).hasSize(64);
  }

  @Test
  void renderedConfEmbedsStandardContainerPaths() {
    when(daemonService.enabledDaemonsForNode("n-1")).thenReturn(List.of(daemon(0)));
    when(daemonService.toServerConfig(any())).thenReturn(ServerConfig.defaults());
    when(daemonService.networkMode()).thenReturn("nat");

    NodeConfigBundleService.NodeConfigBundle bundle = service.bundleForNode("n-1");
    String conf =
        bundle.daemons().stream()
            .filter(e -> e.name().equals("daemon-0.conf"))
            .map(NodeConfigBundleService.FileEntry::content)
            .findFirst()
            .orElseThrow();

    assertThat(conf).contains("ca " + tempDir.resolve("pki/ca.crt"));
    assertThat(conf).contains("crl-verify " + tempDir.resolve("pki/crl.pem"));
    assertThat(conf).contains("client-config-dir " + tempDir.resolve("ccd"));
    assertThat(conf)
        .contains("management 0.0.0.0 7505 " + tempDir.resolve("config/daemon-0.mgmt-pass"));
    assertThat(conf)
        .contains("client-connect " + tempDir.resolve("config/scripts/client-connect.sh"));
  }

  @Test
  void missingPkiFilesAreSkipped() throws Exception {
    when(daemonService.enabledDaemonsForNode("n-1")).thenReturn(List.of());
    when(daemonService.toServerConfig(any())).thenReturn(ServerConfig.defaults());
    when(daemonService.networkMode()).thenReturn("nat");

    Files.delete(tempDir.resolve("pki/server.key"));

    NodeConfigBundleService.NodeConfigBundle bundle = service.bundleForNode("n-1");
    assertThat(bundle.pki())
        .extracting(NodeConfigBundleService.FileEntry::name)
        .doesNotContain("server.key");
  }
}
