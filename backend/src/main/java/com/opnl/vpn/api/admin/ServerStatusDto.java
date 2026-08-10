package com.opnl.vpn.api.admin;

import java.util.List;

/** Snapshot of the panel and its OpenVPN daemons (live status page). */
public record ServerStatusDto(
    String brand,
    String version,
    long uptimeSeconds,
    int activeConnections,
    List<DaemonStatus> daemons) {

  /** Health view of a single daemon. */
  public record DaemonStatus(
      int index,
      String name,
      int port,
      String proto,
      boolean enabled,
      boolean configPresent,
      boolean mgmtReachable) {}
}
