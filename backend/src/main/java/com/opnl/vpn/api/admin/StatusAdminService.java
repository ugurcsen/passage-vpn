package com.opnl.vpn.api.admin;

import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Builds the live server status: brand/version, per-daemon health (config file present, management
 * socket reachable) and the active connection count.
 */
@Service
public class StatusAdminService {

  private static final String FALLBACK_VERSION = "0.1.0-SNAPSHOT";
  private static final int MGMT_PROBE_TIMEOUT_MS = 700;

  private final DaemonService daemonService;
  private final ConnectionRegistry connectionRegistry;
  private final OpnlProperties properties;

  public StatusAdminService(
      DaemonService daemonService,
      ConnectionRegistry connectionRegistry,
      OpnlProperties properties) {
    this.daemonService = daemonService;
    this.connectionRegistry = connectionRegistry;
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
    return new ServerStatusDto.DaemonStatus(
        daemon.getDaemonIndex(),
        daemon.getName(),
        daemon.getPort(),
        daemon.getProto().name().toLowerCase(),
        daemon.isEnabled(),
        Files.exists(configDir.resolve("daemon-" + daemon.getDaemonIndex() + ".conf")),
        mgmtReachable(daemon.getDaemonIndex()));
  }

  /** Opens a short-lived TCP connection to the daemon's management socket. */
  boolean mgmtReachable(int daemonIndex) {
    try (Socket socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(
              properties.openvpn().mgmtHost(), properties.openvpn().mgmtPort() + daemonIndex),
          MGMT_PROBE_TIMEOUT_MS);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** Reads the packaged implementation version, falling back to the project version. */
  private String version() {
    String version = getClass().getPackage().getImplementationVersion();
    return version != null ? version : FALLBACK_VERSION;
  }
}
