package com.passagevpn.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.passagevpn.api.admin.ConnectionDto;
import com.passagevpn.api.admin.MonitorSnapshotDto;
import com.passagevpn.api.admin.ServerStatusDto.DaemonStatus;
import com.passagevpn.api.admin.SystemInfoDto;
import com.passagevpn.api.admin.TrafficPointDto;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.network.ConnectionRegistry;
import com.passagevpn.network.ConnectionRegistry.VpnSession;
import com.passagevpn.network.Daemon;
import com.passagevpn.network.DaemonService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
  private final PassageProperties properties;
  private final ConnectionLogService connectionLogService;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  /** Full poll cadence while at least one status subscriber is connected. */
  private static final Duration ACTIVE_INTERVAL = Duration.ofSeconds(5);

  /**
   * Cadence when nobody is watching: keeps the REST fallback and stale-session reconciliation
   * reasonably fresh without doing management-interface work (DB reads, TCP polls) every 5 seconds.
   */
  private static final Duration IDLE_INTERVAL = Duration.ofSeconds(30);

  private volatile MonitorSnapshotDto latest;
  private volatile Instant lastFullPoll = Instant.EPOCH;
  private volatile String lastBroadcastKey;

  public MonitorService(
      MgmtClientManager clientManager,
      TrafficAggregator aggregator,
      ConnectionRegistry connectionRegistry,
      DaemonService daemonService,
      MonitorBroadcaster broadcaster,
      SystemInfoService systemInfoService,
      PassageProperties properties,
      ConnectionLogService connectionLogService,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.clientManager = clientManager;
    this.aggregator = aggregator;
    this.connectionRegistry = connectionRegistry;
    this.daemonService = daemonService;
    this.broadcaster = broadcaster;
    this.systemInfoService = systemInfoService;
    this.properties = properties;
    this.connectionLogService = connectionLogService;
    this.objectMapper = objectMapper;
  }

  /** Polls management interfaces and broadcasts the fresh snapshot. */
  @Scheduled(fixedDelay = 5_000, initialDelay = 3_000)
  public void poll() {
    Instant now = Instant.now();
    // Nobody is watching: only do the full (DB + TCP) poll on the idle cadence. The cheap
    // scheduler wake-ups in between return immediately.
    if (broadcaster.sessionCount() == 0 && lastFullPoll.plus(IDLE_INTERVAL).isAfter(now)) {
      return;
    }
    lastFullPoll = now;
    Set<String> liveCommonNames = new HashSet<>();
    boolean allDaemonsVisible = true;
    boolean anyDaemonVisible = false;
    try {
      for (Daemon daemon : daemonService.list()) {
        if (!daemon.isEnabled()) {
          continue;
        }
        MgmtStatus status = clientManager.status(daemon.getNodeId(), daemon.getDaemonIndex());
        if (status != null) {
          anyDaemonVisible = true;
          aggregator.update(status.clients(), Instant.now());
          status.clients().forEach(client -> liveCommonNames.add(client.commonName()));
        } else {
          // A daemon's view is missing: never reconcile against a partial picture
          // (an unreachable daemon would look like "zero clients" and wrongly close
          // its live sessions).
          allDaemonsVisible = false;
        }
      }
    } catch (Exception e) {
      log.debug("Monitor poll interrupted: {}", e.getMessage());
    }
    // Reconcile only against a trustworthy, complete picture: at least one enabled daemon
    // answered and no daemon was unreachable. An empty client set is a valid view (no
    // clients connected) and must still close rows left open by a lost disconnect callback.
    if (allDaemonsVisible && anyDaemonVisible) {
      connectionLogService.reconcileOpenSessions(liveCommonNames);
      connectionRegistry.retainOnly(liveCommonNames);
    }
    latest = currentSnapshot();
    if (broadcaster.sessionCount() > 0) {
      try {
        String payload = objectMapper.writeValueAsString(latest);
        // Skip broadcasting when nothing meaningful changed (the `at` timestamp is excluded from
        // the comparison and CPU/disk figures are quantized so idle metrics do not churn the feed).
        String key = broadcastKey(latest);
        if (key == null || !key.equals(lastBroadcastKey)) {
          broadcaster.broadcast(payload);
          lastBroadcastKey = key;
        }
      } catch (JsonProcessingException e) {
        log.warn("Cannot serialize monitor snapshot: {}", e.getMessage());
      }
    }
  }

  /**
   * A stable fingerprint of the snapshot's observable content. Includes the system figures
   * coarse-grained (CPU rounded to a percent, byte figures as-is) so small sensor fluctuations do
   * not count as a change, but real load changes still reach subscribed clients.
   */
  private String broadcastKey(MonitorSnapshotDto snapshot) {
    try {
      SystemInfoDto sys = snapshot.system();
      Object key =
          List.of(
              snapshot.connections(),
              snapshot.daemons(),
              snapshot.bytesInPerSec(),
              snapshot.bytesOutPerSec(),
              snapshot.activeConnections(),
              snapshot.history(),
              Math.round(sys.cpuLoadPercent()),
              sys.totalMemory(),
              sys.freeMemory(),
              sys.diskTotal(),
              sys.diskFree(),
              sys.availableProcessors());
      return objectMapper.writeValueAsString(key);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /** Builds a snapshot from the current registry, aggregator and management caches. */
  public MonitorSnapshotDto currentSnapshot() {
    Instant now = Instant.now();
    List<ConnectionDto> connections =
        connectionRegistry.sessions().stream().map(this::withTraffic).toList();
    List<DaemonStatus> daemons = daemonService.list().stream().map(this::toDaemonStatus).toList();
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
    TrafficAggregator.SessionTraffic traffic =
        aggregator.trafficFor(session.commonName()).orElse(null);
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
    MgmtStatus cached = clientManager.cachedStatus(daemon.getNodeId(), daemon.getDaemonIndex());
    boolean reachable = cached != null;
    Boolean dco = reachable ? cached.dco() : null;
    // Config presence is only meaningful for the local deployment: remote nodes
    // generate and serve their own configs, which are not in the local volume.
    boolean configPresent =
        daemon.getNodeId() == null
            && Files.exists(
                Path.of(properties.openvpn().configDir())
                    .resolve("daemon-" + daemon.getDaemonIndex() + ".conf"));
    return new DaemonStatus(
        daemon.getDaemonIndex(),
        daemon.getName(),
        daemon.getPort(),
        daemon.getProto().name().toLowerCase(),
        daemon.isEnabled(),
        configPresent,
        reachable,
        dco,
        daemon.getNodeId());
  }
}
