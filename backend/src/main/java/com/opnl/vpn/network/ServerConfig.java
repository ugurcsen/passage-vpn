package com.opnl.vpn.network;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Network-level server configuration for a single OpenVPN daemon. Persisted as a JSON string and
 * rendered into a daemon.conf by {@link ServerConfigGenerator}.
 */
public record ServerConfig(
    @Min(0) @Max(31) int daemonIndex,
    @Min(1) @Max(65535) int port,
    Protocol proto,
    @NotBlank String subnet,
    @NotBlank String subnetMask,
    List<String> dnsServers,
    String domain,
    List<String> extraRoutes,
    boolean fullTunnel,
    boolean clientCertNotRequired,
    boolean authUserPass,
    String adminHost,
    boolean ipv6Enabled,
    String ipv6Subnet) {

  public enum Protocol {
    udp,
    tcp,
    udp6,
    tcp6
  }

  /** True when the IPv6 transport protocol (udp6/tcp6) was chosen. */
  public boolean ipv6Transport() {
    return proto == Protocol.udp6 || proto == Protocol.tcp6;
  }

  /** Defaults for a fresh install: daemon 0, 10.8.0.0/24, UDP 1194, IPv6 off. */
  public static ServerConfig defaults() {
    return new ServerConfig(
        0,
        1194,
        Protocol.udp,
        "10.8.0.0",
        "255.255.255.0",
        List.of("1.1.1.1", "8.8.8.8"),
        null,
        List.of(),
        true,
        false,
        true,
        "vpn.example.com",
        false,
        null);
  }
}
