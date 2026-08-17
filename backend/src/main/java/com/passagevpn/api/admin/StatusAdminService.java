package com.passagevpn.api.admin;

import com.passagevpn.config.PassageProperties;
import com.passagevpn.monitor.MgmtClientManager;
import com.passagevpn.monitor.MgmtStatus;
import com.passagevpn.network.ConnectionRegistry;
import com.passagevpn.network.Daemon;
import com.passagevpn.network.DaemonService;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Builds the live server status: brand/version, per-daemon health (config file present, management
 * session live) and the active connection count.
 */
@Service
public class StatusAdminService {

  private static final String FALLBACK_VERSION = "0.1.0-SNAPSHOT";

  /**
   * A management status is considered live when the monitoring layer polled it at most this long
   * ago. Covers the slowest poll cadence (30s idle) with slack for scheduler jitter.
   */
  private static final Duration MGMT_STALE_AFTER = Duration.ofSeconds(90);

  private final DaemonService daemonService;
  private final ConnectionRegistry connectionRegistry;
  private final MgmtClientManager mgmtClientManager;
  private final PassageProperties properties;

  public StatusAdminService(
      DaemonService daemonService,
      ConnectionRegistry connectionRegistry,
      MgmtClientManager mgmtClientManager,
      PassageProperties properties) {
    this.daemonService = daemonService;
    this.connectionRegistry = connectionRegistry;
    this.mgmtClientManager = mgmtClientManager;
    this.properties = properties;
  }

  public ServerStatusDto status() {
    Path configDir = Path.of(properties.openvpn().configDir());
    List<ServerStatusDto.DaemonStatus> daemons =
        daemonService.list().stream().map(d -> toDaemonStatus(d, configDir)).toList();
    return new ServerStatusDto(
        properties.brandName(),
        version(),
        ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
        connectionRegistry.sessions().size(),
        daemons);
  }

  private ServerStatusDto.DaemonStatus toDaemonStatus(Daemon daemon, Path configDir) {
    MgmtStatus cached = mgmtClientManager.cachedStatus(daemon.getNodeId(), daemon.getDaemonIndex());
    Boolean dco = cached != null ? cached.dco() : null;
    boolean configPresent =
        daemon.getNodeId() == null
            && Files.exists(configDir.resolve("daemon-" + daemon.getDaemonIndex() + ".conf"));
    return new ServerStatusDto.DaemonStatus(
        daemon.getDaemonIndex(),
        daemon.getName(),
        daemon.getPort(),
        daemon.getProto().name().toLowerCase(),
        daemon.isEnabled(),
        configPresent,
        mgmtReachable(daemon.getNodeId(), daemon.getDaemonIndex()),
        dco,
        daemon.getNodeId());
  }

  /**
   * A daemon is reachable when the monitoring layer last polled a fresh status over its persistent
   * management session. Deliberately opens no new connection: OpenVPN's management interface only
   * services a single session at a time, so a per-call probe queues up dead sockets and eventually
   * starves the real monitor.
   */
  boolean mgmtReachable(String nodeId, int daemonIndex) {
    MgmtStatus cached = mgmtClientManager.cachedStatus(nodeId, daemonIndex);
    return cached != null && cached.at().isAfter(Instant.now().minus(MGMT_STALE_AFTER));
  }

  /** Reads the packaged implementation version, falling back to the project version. */
  private String version() {
    String version = getClass().getPackage().getImplementationVersion();
    return version != null ? version : FALLBACK_VERSION;
  }
}
