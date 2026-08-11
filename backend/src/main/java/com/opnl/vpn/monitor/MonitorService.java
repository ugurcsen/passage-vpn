package com.opnl.vpn.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.opnl.vpn.api.admin.ConnectionDto;
import com.opnl.vpn.api.admin.MonitorSnapshotDto;
import com.opnl.vpn.api.admin.ServerStatusDto.DaemonStatus;
import com.opnl.vpn.api.admin.TrafficPointDto;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.ConnectionRegistry.VpnSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Polls every enabled daemon's management interface, feeds the {@link TrafficAggregator}, builds
 * the {@link MonitorSnapshotDto} and broadcasts it to status WebSocket clients. The latest snapshot
 * is cached for the REST fallback and for new WebSocket subscribers.
 */
@Slf4j
@Service
public class MonitorService {

  private final MgmtClientManager clientManager;
  private final TrafficAggregator aggregator;
  private final ConnectionRegistry connectionRegistry;
  private final DaemonService daemonService;
  private final MonitorBroadcaster broadcaster;
  private final SystemInfoService systemInfoService;
  private final OpnlProperties properties;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  private volatile MonitorSnapshotDto latest;

  public MonitorService(
      MgmtClientManager clientManager,
      TrafficAggregator aggregator,
      ConnectionRegistry connectionRegistry,
      DaemonService daemonService,
      MonitorBroadcaster broadcaster,
      SystemInfoService systemInfoService,
      OpnlProperties properties,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.clientManager = clientManager;
    this.aggregator = aggregator;
    this.connectionRegistry = connectionRegistry;
    this.daemonService = daemonService;
    this.broadcaster = broadcaster;
    this.systemInfoService = systemInfoService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /** Polls management interfaces and broadcasts the fresh snapshot. */
  @Scheduled(fixedDelay = 5_000, initialDelay = 3_000)
  public void poll() {
    try {
      for (Daemon daemon : daemonService.list()) {
        if (!daemon.isEnabled()) {
          continue;
        }
        MgmtStatus status = clientManager.status(daemon.getDaemonIndex());
        if (status != null) {
          aggregator.update(status.clients(), Instant.now());
        }
      }
    } catch (Exception e) {
      log.debug("Monitor poll interrupted: {}", e.getMessage());
    }
    latest = currentSnapshot();
    if (broadcaster.sessionCount() > 0) {
      try {
        broadcaster.broadcast(objectMapper.writeValueAsString(latest));
      } catch (JsonProcessingException e) {
        log.warn("Cannot serialize monitor snapshot: {}", e.getMessage());
      }
    }
  }

  /** Builds a snapshot from the current registry, aggregator and management caches. */
  public MonitorSnapshotDto currentSnapshot() {
    Instant now = Instant.now();
    List<ConnectionDto> connections =
        connectionRegistry.sessions().stream()
            .map(this::withTraffic)
            .toList();
    List<DaemonStatus> daemons =
        daemonService.list().stream().map(this::toDaemonStatus).toList();
    List<TrafficPointDto> history =
        aggregator.history().stream()
            .map(
                point ->
                    new TrafficPointDto(
                        point.at(),
                        point.bytesInPerSec(),
                        point.bytesOutPerSec(),
                        point.activeConnections()))
            .toList();
    TrafficPointDto last = history.isEmpty() ? null : history.get(history.size() - 1);
    long bytesInPerSec = last == null ? 0 : last.bytesInPerSec();
    long bytesOutPerSec = last == null ? 0 : last.bytesOutPerSec();
    return new MonitorSnapshotDto(
        now,
        connections,
        daemons,
        bytesInPerSec,
        bytesOutPerSec,
        connections.size(),
        history,
        systemInfoService.systemInfo());
  }

  /** The most recently built snapshot (REST fallback for clients without WebSocket). */
  public MonitorSnapshotDto latestSnapshot() {
    MonitorSnapshotDto current = latest;
    return current != null ? current : currentSnapshot();
  }

  private ConnectionDto withTraffic(VpnSession session) {
    TrafficAggregator.SessionTraffic traffic = aggregator.trafficFor(session.commonName()).orElse(null);
    if (traffic == null) {
      return ConnectionDto.from(session);
    }
    return ConnectionDto.from(
        session,
        traffic.bytesIn(),
        traffic.bytesOut(),
        traffic.bytesInPerSec(),
        traffic.bytesOutPerSec());
  }

  private DaemonStatus toDaemonStatus(Daemon daemon) {
    MgmtStatus cached = clientManager.cachedStatus(daemon.getDaemonIndex());
    boolean reachable = cached != null;
    Boolean dco = reachable ? cached.dco() : null;
    return new DaemonStatus(
        daemon.getDaemonIndex(),
        daemon.getName(),
        daemon.getPort(),
        daemon.getProto().name().toLowerCase(),
        daemon.isEnabled(),
        Files.exists(Path.of(properties.openvpn().configDir()).resolve("daemon-" + daemon.getDaemonIndex() + ".conf")),
        reachable,
        dco);
  }
}
