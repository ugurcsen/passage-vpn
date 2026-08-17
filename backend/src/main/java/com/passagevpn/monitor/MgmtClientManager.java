package com.passagevpn.monitor;

import com.passagevpn.config.PassageProperties;
import com.passagevpn.network.NodeRegistryService;
import com.passagevpn.network.OpenVpnNode;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Owns one {@link MgmtClient} per (node, daemon index). The local deployment uses {@code mgmtPort +
 * index} from {@code opnl.openvpn}; daemons assigned to a registered node use that node's {@code
 * mgmtHost} and {@code mgmtPortBase + index}. Connections are established lazily on first use and
 * transparently re-established after failures; a per-endpoint cooldown prevents reconnect storms
 * while a daemon is down.
 */
@Slf4j
@Component
public class MgmtClientManager {

  private static final long RETRY_COOLDOWN_MS = 10_000;

  /** Identifies a management endpoint uniquely: a node (null = local) and a daemon index. */
  public record MgmtEndpoint(String nodeId, int daemonIndex) {}

  private final PassageProperties properties;
  private final NodeRegistryService nodeRegistryService;
  private final ConcurrentHashMap<MgmtEndpoint, MgmtClient> clients = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<MgmtEndpoint, MgmtStatus> cachedStatus =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<MgmtEndpoint, Long> lastAttemptAt = new ConcurrentHashMap<>();

  public MgmtClientManager(PassageProperties properties, NodeRegistryService nodeRegistryService) {
    this.properties = properties;
    this.nodeRegistryService = nodeRegistryService;
  }

  /**
   * Polls {@code status 3} for the given (node, daemon), caching the result. Returns {@code null}
   * when the daemon's management socket is unreachable.
   */
  public synchronized MgmtStatus status(String nodeId, int daemonIndex) {
    MgmtClient client = clientFor(nodeId, daemonIndex);
    if (client == null) {
      return null;
    }
    MgmtStatus status = client.status();
    if (status != null) {
      cachedStatus.put(new MgmtEndpoint(nodeId, daemonIndex), status);
    }
    return status;
  }

  /** Convenience for the local deployment. */
  public synchronized MgmtStatus status(int daemonIndex) {
    return status(null, daemonIndex);
  }

  /** Last successfully polled status for a (node, daemon), or {@code null} if never reached. */
  public MgmtStatus cachedStatus(String nodeId, int daemonIndex) {
    return cachedStatus.get(new MgmtEndpoint(nodeId, daemonIndex));
  }

  /** Convenience for the local deployment. */
  public MgmtStatus cachedStatus(int daemonIndex) {
    return cachedStatus(null, daemonIndex);
  }

  /** Disconnects the given common name on the first reachable daemon that acknowledges the kill. */
  public boolean kill(String commonName) {
    for (MgmtClient client : clients.values()) {
      if (client.kill(commonName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Sends a management {@code signal} command to a single (node, daemon). Returns whether the
   * daemon acknowledged it; {@code false} also when the daemon is unreachable or in its reconnect
   * cooldown.
   */
  public synchronized boolean signal(String nodeId, int daemonIndex, String signal) {
    MgmtClient client = clientFor(nodeId, daemonIndex);
    return client != null && client.signal(signal);
  }

  /** Convenience for the local deployment. */
  public synchronized boolean signal(int daemonIndex, String signal) {
    return signal(null, daemonIndex, signal);
  }

  private MgmtClient clientFor(String nodeId, int daemonIndex) {
    MgmtEndpoint endpoint = new MgmtEndpoint(nodeId, daemonIndex);
    MgmtClient existing = clients.get(endpoint);
    if (existing != null && existing.isConnected()) {
      return existing;
    }
    long now = System.currentTimeMillis();
    Long last = lastAttemptAt.get(endpoint);
    if (last != null && now - last < RETRY_COOLDOWN_MS) {
      return null;
    }
    lastAttemptAt.put(endpoint, now);

    String host;
    int port;
    String password;
    if (nodeId == null) {
      host = properties.openvpn().mgmtHost();
      port = properties.openvpn().mgmtPort() + daemonIndex;
      password = properties.openvpn().mgmtPassword();
    } else {
      Optional<OpenVpnNode> node = nodeRegistryService.findNode(nodeId);
      if (node.isEmpty() || !node.get().isEnabled()) {
        return null;
      }
      OpenVpnNode gateway = node.get();
      host = gateway.getMgmtHost();
      port = gateway.getMgmtPortBase() + daemonIndex;
      password = gateway.getMgmtPassword();
    }
    if (password == null || password.isBlank()) {
      log.warn(
          "Skipping management connection to {}:{} (node={}, daemon {}): no management password configured",
          host,
          port,
          nodeId == null ? "local" : nodeId,
          daemonIndex);
      return null;
    }
    MgmtClient fresh = new MgmtClient(host, port, daemonIndex, nodeId, password);
    if (fresh.connect()) {
      clients.put(endpoint, fresh);
      return fresh;
    }
    return null;
  }
}
