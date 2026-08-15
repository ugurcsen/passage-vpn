package com.opnl.vpn.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.opnl.vpn.network.ServerConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class OvpnGeneratorTest {

  private final OvpnGenerator generator = new OvpnGenerator();

  private ServerConfig config(boolean ipv6Enabled) {
    return new ServerConfig(
        0,
        1194,
        ServerConfig.Protocol.udp,
        "10.8.0.0",
        "255.255.255.0",
        List.of("1.1.1.1"),
        null,
        List.of(),
        true,
        false,
        true,
        "vpn.example.com",
        ipv6Enabled,
        ipv6Enabled ? "fd00:1::/64" : null);
  }

  private OvpnGenerator.Endpoint endpoint(ServerConfig config, String host) {
    return new OvpnGenerator.Endpoint(host, config.port(), config.proto(), config.ipv6Enabled());
  }

  @Test
  void rendersIpv6DirectivesWhenDualStack() {
    String profile =
        generator.render(
            ProfileType.GENERIC,
            List.of(endpoint(config(true), "vpn.example.com")),
            "CA",
            "TA",
            "",
            "",
            false,
            false);
    assertThat(profile).contains("tun-ipv6");
    assertThat(profile).contains("redirect-gateway ipv6");
  }

  @Test
  void omitsIpv6DirectivesForIpv4OnlyServer() {
    String profile =
        generator.render(
            ProfileType.GENERIC,
            List.of(endpoint(config(false), "vpn.example.com")),
            "CA",
            "TA",
            "",
            "",
            false,
            false);
    assertThat(profile).doesNotContain("tun-ipv6");
    assertThat(profile).doesNotContain("redirect-gateway ipv6");
  }

  @Test
  void singleEndpointRendersLegacyProtoBlock() {
    String profile =
        generator.render(
            ProfileType.GENERIC,
            List.of(endpoint(config(false), "vpn.example.com")),
            "CA",
            "TA",
            "",
            "",
            false,
            true);
    assertThat(profile).contains("proto udp").contains("remote vpn.example.com 1194");
    assertThat(profile).doesNotContain("remote-random");
  }

  @Test
  void multiRemoteRendersEveryEndpointWithProtoAndRandom() {
    OvpnGenerator.Endpoint tcp =
        new OvpnGenerator.Endpoint("vpn-us.example.com", 1195, ServerConfig.Protocol.tcp, false);
    String profile =
        generator.render(
            ProfileType.GENERIC,
            List.of(endpoint(config(false), "vpn-eu.example.com"), tcp),
            "CA",
            "TA",
            "",
            "",
            false,
            true);
    assertThat(profile)
        .contains("remote vpn-eu.example.com 1194 udp")
        .contains("remote vpn-us.example.com 1195 tcp")
        .contains("remote-random");
    assertThat(profile).doesNotContain("proto udp\n");
  }

  @Test
  void multiRemoteDisabledUsesFirstEndpointOnly() {
    OvpnGenerator.Endpoint second =
        new OvpnGenerator.Endpoint("vpn-us.example.com", 1195, ServerConfig.Protocol.tcp, false);
    String profile =
        generator.render(
            ProfileType.GENERIC,
            List.of(endpoint(config(false), "vpn-eu.example.com"), second),
            "CA",
            "TA",
            "",
            "",
            false,
            false);
    assertThat(profile).contains("remote vpn-eu.example.com 1194");
    assertThat(profile).doesNotContain("remote vpn-us.example.com");
    assertThat(profile).doesNotContain("remote-random");
  }

  @Test
  void throwsWhenNoEndpointsProvided() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                generator.render(ProfileType.GENERIC, List.of(), "CA", "TA", "", "", false, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
