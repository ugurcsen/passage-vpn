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

  @Test
  void rendersIpv6DirectivesWhenDualStack() {
    String profile =
        generator.render(
            ProfileType.GENERIC, config(true), "vpn.example.com", "CA", "TA", "", "", false);
    assertThat(profile).contains("tun-ipv6");
    assertThat(profile).contains("redirect-gateway ipv6");
  }

  @Test
  void omitsIpv6DirectivesForIpv4OnlyServer() {
    String profile =
        generator.render(
            ProfileType.GENERIC, config(false), "vpn.example.com", "CA", "TA", "", "", false);
    assertThat(profile).doesNotContain("tun-ipv6");
    assertThat(profile).doesNotContain("redirect-gateway ipv6");
  }
}
