package com.opnl.vpn.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerConfigGeneratorTest {

  private ServerConfigGenerator generator;

  @BeforeEach
  void setUp() {
    generator = new ServerConfigGenerator(new ObjectMapper());
  }

  @Test
  void fullTunnelPushesRedirectGateway() {
    String conf =
        generator.render(
            ServerConfig.defaults(),
            "/pki",
            "/ccd",
            "/scripts",
            "/logs",
            "nat",
            "/config/daemon-0.mgmt-pass");
    assertThat(conf).contains("push \"redirect-gateway def1 bypass-dhcp\"");
    assertThat(conf).contains("port 1194");
    assertThat(conf).contains("proto udp");
    assertThat(conf).contains("server 10.8.0.0 255.255.255.0");
    assertThat(conf).contains("management 0.0.0.0 7505 /config/daemon-0.mgmt-pass");
    // management-signal must never be rendered: it would SIGUSR1-restart the
    // daemon (dropping all sessions) whenever the management client disconnects.
    assertThat(conf).doesNotContain("management-signal");
    assertThat(conf).contains("auth-user-pass-verify /scripts/verify-user-pass.sh via-env");
    assertThat(conf).contains("client-crresponse /scripts/verify-user-pass.sh");
    assertThat(conf).contains("auth-gen-token 43200");
  }

  @Test
  void splitTunnelOmitsRedirectGateway() {
    ServerConfig split =
        new ServerConfig(
            1,
            1195,
            ServerConfig.Protocol.tcp,
            "10.9.0.0",
            "255.255.255.0",
            java.util.List.of("9.9.9.9"),
            "corp.example.com",
            java.util.List.of("192.168.10.0 255.255.255.0"),
            false,
            false,
            true,
            "vpn.example.com",
            false,
            null);
    String conf =
        generator.render(
            split, "/pki", "/ccd", "/scripts", "/logs", "nat", "/config/daemon-0.mgmt-pass");
    assertThat(conf).doesNotContain("redirect-gateway");
    assertThat(conf).contains("proto tcp");
    assertThat(conf).contains("port 1195");
    assertThat(conf).contains("push \"dhcp-option DNS 10.9.0.1\"");
    assertThat(conf).contains("push \"dhcp-option DNS 9.9.9.9\"");
    assertThat(conf).contains("push \"dhcp-option DOMAIN corp.example.com\"");
    assertThat(conf).contains("push \"route 192.168.10.0 255.255.255.0\"");
    assertThat(conf).contains("management 0.0.0.0 7506");
  }

  @Test
  void dnsmasqServerIpComputesTunAddress() {
    assertThat(ServerConfigGenerator.dnsmasqServerIp("10.8.0.0")).isEqualTo("10.8.0.1");
    assertThat(ServerConfigGenerator.dnsmasqServerIp("10.9.0.0")).isEqualTo("10.9.0.1");
    assertThat(ServerConfigGenerator.dnsmasqServerIp("10.9.0.255")).isEqualTo("10.9.1.0");
    assertThat(ServerConfigGenerator.dnsmasqServerIp(null)).isNull();
    assertThat(ServerConfigGenerator.dnsmasqServerIp("")).isNull();
    assertThat(ServerConfigGenerator.dnsmasqServerIp("not-an-ip")).isNull();
    assertThat(ServerConfigGenerator.dnsmasqServerIp("1.2.3.300")).isNull();
    assertThat(ServerConfigGenerator.dnsmasqServerIp("255.255.255.255")).isNull();
  }

  @Test
  void genericDaemonDisablesClientCertVerification() {
    ServerConfig generic =
        new ServerConfig(
            2,
            1196,
            ServerConfig.Protocol.udp,
            "10.10.0.0",
            "255.255.255.0",
            java.util.List.of("1.1.1.1"),
            null,
            java.util.List.of(),
            true,
            true,
            true,
            "vpn.example.com",
            false,
            null);
    String conf =
        generator.render(
            generic, "/pki", "/ccd", "/scripts", "/logs", "nat", "/config/daemon-0.mgmt-pass");
    assertThat(conf).contains("verify-client-cert none");
    assertThat(conf).doesNotContain("client-cert-not-required");
  }

  @Test
  void renderSurfacesNetworkMode() {
    String routed =
        generator.render(
            ServerConfig.defaults(),
            "/pki",
            "/ccd",
            "/scripts",
            "/logs",
            "routed",
            "/config/daemon-0.mgmt-pass");
    assertThat(routed).contains("# network-mode routed");
    String nat =
        generator.render(
            ServerConfig.defaults(),
            "/pki",
            "/ccd",
            "/scripts",
            "/logs",
            "nat",
            "/config/daemon-0.mgmt-pass");
    assertThat(nat).contains("# network-mode nat");
    String blank =
        generator.render(
            ServerConfig.defaults(),
            "/pki",
            "/ccd",
            "/scripts",
            "/logs",
            null,
            "/config/daemon-0.mgmt-pass");
    assertThat(blank).contains("# network-mode nat");
  }

  @Test
  void jsonRoundTrip() {
    ServerConfig original = ServerConfig.defaults();
    String json = generator.toJson(original);
    ServerConfig parsed = generator.fromJson(json);
    assertThat(parsed).isEqualTo(original);
  }

  @Test
  void invalidJsonFallsBackToDefaults() {
    assertThat(generator.fromJson("{not-json")).isEqualTo(ServerConfig.defaults());
  }

  @Test
  void renderSubstitutesEveryPlaceholder() {
    String conf =
        generator.render(
            ServerConfig.defaults(),
            "/pki",
            "/ccd",
            "/scripts",
            "/logs",
            "nat",
            "/config/daemon-0.mgmt-pass");
    for (String token :
        List.of(
            "__PORT__",
            "__PROTO__",
            "__MGMT_PORT__",
            "__SUBNET__",
            "__SUBNET_MASK__",
            "__NETWORK_MODE__",
            "__PKI_DIR__",
            "__CCD_DIR__",
            "__SCRIPTS_DIR__",
            "__LOG_DIR__",
            "__AUTH_VERIFY__",
            "__VERIFY_CLIENT_CERT__",
            "__DNS_PUSH__",
            "__ROUTE_PUSH__",
            "__EXTRA_PUSH__")) {
      assertThat(conf).doesNotContain(token);
    }
  }

  @Test
  void renderDnsmasqConfigPinsEveryResolvedAddress() {
    String conf =
        generator.renderDnsmasqConfig(
            Map.of(
                "api.github.com", Set.of("140.82.112.5", "140.82.113.5"),
                "www.example.com", Set.of("93.184.215.14")));

    assertThat(conf)
        .contains("address=/api.github.com/140.82.112.5")
        .contains("address=/api.github.com/140.82.113.5")
        .contains("server=/api.github.com/")
        .contains("address=/www.example.com/93.184.215.14")
        .contains("server=/www.example.com/");
  }

  @Test
  void renderDnsmasqConfigSkipsUnresolvedDomains() {
    String conf =
        generator.renderDnsmasqConfig(
            Map.of("up.example.com", Set.of(), "ok.example.com", Set.of("1.2.3.4")));

    assertThat(conf)
        .doesNotContain("up.example.com")
        .contains("address=/ok.example.com/1.2.3.4")
        .contains("server=/ok.example.com/");
  }
}
