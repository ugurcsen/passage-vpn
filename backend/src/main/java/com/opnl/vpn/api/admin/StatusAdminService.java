package com.opnl.vpn.api.admin;

import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.monitor.MgmtClientManager;
import com.opnl.vpn.monitor.MgmtStatus;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.NodeRegistryService;
import com.opnl.vpn.network.OpenVpnNode;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Builds the live server status: brand/version, per-daemon health (config file present, management
 * socket reachable) and the active connection count. Daemons assigned to a registered node are
 * probed against that node's management endpoint instead of the local socket.
 */
@Service
public class StatusAdminService {

  private static final String FALLBACK_VERSION = "0.1.0-SNAPSHOT";
  private static final int MGMT_PROBE_TIMEOUT_MS = 700;

  private final DaemonService daemonService;
  private final ConnectionRegistry connectionRegistry;
  private final MgmtClientManager mgmtClientManager;
  private final NodeRegistryService nodeRegistryService;
  private final OpnlProperties properties;

  public StatusAdminService(
      DaemonService daemonService,
      ConnectionRegistry connectionRegistry,
      MgmtClientManager mgmtClientManager,
      NodeRegistryService nodeRegistryService,
      OpnlProperties properties) {
    this.daemonService = daemonService;
    this.connectionRegistry = connectionRegistry;
    this.mgmtClientManager = mgmtClientManager;
    this.nodeRegistryService = nodeRegistryService;
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

  /** Opens a short-lived TCP connection to the daemon's management socket. */
  boolean mgmtReachable(String nodeId, int daemonIndex) {
    Optional<OpenVpnNode> node =
        nodeId == null ? Optional.empty() : nodeRegistryService.findNode(nodeId);
    String host;
    int port;
    if (node.isEmpty()) {
      host = properties.openvpn().mgmtHost();
      port = properties.openvpn().mgmtPort() + daemonIndex;
    } else {
      host = node.get().getMgmtHost();
      port = node.get().getMgmtPortBase() + daemonIndex;
    }
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), MGMT_PROBE_TIMEOUT_MS);
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
