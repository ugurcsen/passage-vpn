package com.passagevpn.api.admin;

import java.time.Instant;
import java.util.List;

/**
 * Live monitoring snapshot pushed over the status WebSocket and available from the monitor REST
 * endpoint: active connections with traffic, daemon health with DCO flags, aggregate throughput and
 * the traffic history ring for the dashboard chart.
 */
public record MonitorSnapshotDto(
    Instant at,
    List<ConnectionDto> connections,
    List<ServerStatusDto.DaemonStatus> daemons,
    long bytesInPerSec,
    long bytesOutPerSec,
    int activeConnections,
    List<TrafficPointDto> history,
    SystemInfoDto system) {}
