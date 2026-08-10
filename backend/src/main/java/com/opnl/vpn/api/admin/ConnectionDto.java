package com.opnl.vpn.api.admin;

import com.opnl.vpn.network.ConnectionRegistry.VpnSession;
import java.time.Instant;

/** Admin-facing view of an active VPN connection (Phase 4 dashboard data source). */
public record ConnectionDto(
    String username, String commonName, String virtualIp, String remoteIp, Instant connectedAt) {

  public static ConnectionDto from(VpnSession session) {
    return new ConnectionDto(
        session.username(),
        session.commonName(),
        session.virtualIp(),
        session.remoteIp(),
        session.connectedAt());
  }
}
