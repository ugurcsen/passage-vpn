package com.opnl.vpn.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    String conf = generator.render(ServerConfig.defaults(), "/pki", "/ccd", "/scripts", "/logs");
    assertThat(conf).contains("push \"redirect-gateway def1 bypass-dhcp\"");
    assertThat(conf).contains("port 1194");
    assertThat(conf).contains("proto udp");
    assertThat(conf).contains("server 10.8.0.0 255.255.255.0");
    assertThat(conf).contains("management 0.0.0.0 7505");
    assertThat(conf).contains("auth-user-pass-verify /scripts/verify-user-pass.sh via-env");
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
            "vpn.example.com");
    String conf = generator.render(split, "/pki", "/ccd", "/scripts", "/logs");
    assertThat(conf).doesNotContain("redirect-gateway");
    assertThat(conf).contains("proto tcp");
    assertThat(conf).contains("port 1195");
    assertThat(conf).contains("push \"dhcp-option DNS 9.9.9.9\"");
    assertThat(conf).contains("push \"dhcp-option DOMAIN corp.example.com\"");
    assertThat(conf).contains("push \"route 192.168.10.0 255.255.255.0\"");
    assertThat(conf).contains("management 0.0.0.0 7506");
  }

  @Test
  void genericDaemonAddsClientCertNotRequired() {
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
            "vpn.example.com");
    String conf = generator.render(generic, "/pki", "/ccd", "/scripts", "/logs");
    assertThat(conf).contains("client-cert-not-required");
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
}
