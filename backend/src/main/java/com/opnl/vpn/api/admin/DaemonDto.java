package com.opnl.vpn.api.admin;

import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.ServerConfig.Protocol;
import java.time.Instant;
import java.util.List;

/** Admin-facing view of an OpenVPN daemon. */
public record DaemonDto(
    String id,
    int daemonIndex,
    String name,
    int port,
    Protocol proto,
    String subnet,
    String subnetMask,
    List<String> dnsServers,
    String domain,
    List<String> extraRoutes,
    boolean fullTunnel,
    boolean clientCertNotRequired,
    boolean authUserPass,
    String adminHost,
    boolean enabled,
    boolean primary,
    Instant createdAt) {

  public static DaemonDto from(Daemon daemon) {
    return new DaemonDto(
        daemon.getId(),
        daemon.getDaemonIndex(),
        daemon.getName(),
        daemon.getPort(),
        daemon.getProto(),
        daemon.getSubnet(),
        daemon.getSubnetMask(),
        daemon.getDnsServers(),
        daemon.getDomain(),
        daemon.getExtraRoutes(),
        daemon.isFullTunnel(),
        daemon.isClientCertNotRequired(),
        daemon.isAuthUserPass(),
        daemon.getAdminHost(),
        daemon.isEnabled(),
        daemon.getDaemonIndex() == 0,
        daemon.getCreatedAt());
  }
}
