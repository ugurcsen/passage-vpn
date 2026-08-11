package com.opnl.vpn.api.admin;

import com.opnl.vpn.network.ConnectionRegistry.VpnSession;
import java.time.Instant;

/**
 * Admin-facing view of an active VPN connection. Traffic fields are nullable: they are populated
 * from management-interface polls and are absent for sessions the backend has not reached.
 */
public record ConnectionDto(
    String username,
    String commonName,
    String virtualIp,
    String remoteIp,
    String daemonName,
    Instant connectedAt,
    Long bytesIn,
    Long bytesOut,
    Long bytesInPerSec,
    Long bytesOutPerSec) {

  public static ConnectionDto from(VpnSession session) {
    return from(session, null, null, null, null);
  }

  public static ConnectionDto from(
      VpnSession session, Long bytesIn, Long bytesOut, Long bytesInPerSec, Long bytesOutPerSec) {
    return new ConnectionDto(
        session.username(),
        session.commonName(),
        session.virtualIp(),
        session.remoteIp(),
        session.daemonName(),
        session.connectedAt(),
        bytesIn,
        bytesOut,
        bytesInPerSec,
        bytesOutPerSec);
  }
}
