package com.opnl.vpn.api.admin;

import com.opnl.vpn.monitor.ConnectionLog;
import java.time.Instant;

/** Admin-facing view of a persisted VPN session. */
public record ConnectionLogDto(
    String username,
    String commonName,
    String virtualIp,
    String remoteIp,
    String daemonName,
    Instant connectedAt,
    Instant disconnectedAt,
    long bytesIn,
    long bytesOut,
    long durationSeconds) {

  public static ConnectionLogDto from(ConnectionLog log) {
    long duration =
        log.getDisconnectedAt() == null
            ? 0
            : Math.max(
                0,
                log.getDisconnectedAt().getEpochSecond() - log.getConnectedAt().getEpochSecond());
    return new ConnectionLogDto(
        log.getUsername(),
        log.getCommonName(),
        log.getVirtualIp(),
        log.getRemoteIp(),
        log.getDaemonName(),
        log.getConnectedAt(),
        log.getDisconnectedAt(),
        log.getBytesIn(),
        log.getBytesOut(),
        duration);
  }
}
