package com.opnl.vpn.network;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of active VPN connections. Populated by the internal connect/disconnect and
 * learn-address script callbacks; read by the admin monitoring endpoint (Phase 4 UI). Not
 * persisted: restarts clear live sessions, which is acceptable for a single-instance deployment.
 */
@Component
public class ConnectionRegistry {

  /** An active client tunnel as correlated from connect/disconnect and learn-address events. */
  public record VpnSession(
      String username,
      String commonName,
      String virtualIp,
      String virtualIpv6,
      String remoteIp,
      String daemonName,
      String nodeId,
      Instant connectedAt) {}

  private final ConcurrentHashMap<String, VpnSession> byCommonName = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, VpnSession> byVirtualIp = new ConcurrentHashMap<>();

  /** Registers or refreshes a session after client-connect (local deployment). */
  public void register(
      String username,
      String commonName,
      String virtualIp,
      String virtualIpv6,
      String remoteIp,
      String daemonName) {
    register(username, commonName, virtualIp, virtualIpv6, remoteIp, daemonName, null);
  }

  /** Registers or refreshes a session after client-connect. */
  public void register(
      String username,
      String commonName,
      String virtualIp,
      String virtualIpv6,
      String remoteIp,
      String daemonName,
      String nodeId) {
    if (commonName == null || commonName.isBlank()) {
      return;
    }
    VpnSession session =
        new VpnSession(
            username,
            commonName,
            virtualIp,
            virtualIpv6,
            remoteIp,
            daemonName,
            nodeId,
            Instant.now());
    byCommonName.put(key(nodeId, commonName), session);
    if (virtualIp != null && !virtualIp.isBlank()) {
      byVirtualIp.put(key(nodeId, virtualIp), session);
    }
    if (virtualIpv6 != null && !virtualIpv6.isBlank()) {
      byVirtualIp.put(key(nodeId, virtualIpv6), session);
    }
  }

  /**
   * Applies a learn-address event (add/update/delete) mapping a virtual IP to a common name.
   * OpenVPN calls this whenever a client's address is learned or released.
   */
  public void learn(String operation, String address, String commonName) {
    if (address == null || address.isBlank()) {
      return;
    }
    VpnSession existing = byVirtualIp.get(key(null, address));
    if ("delete".equalsIgnoreCase(operation)) {
      byVirtualIp.remove(key(null, address));
      if (existing != null) {
        byCommonName.remove(key(null, existing.commonName()), existing);
      }
      return;
    }
    VpnSession session =
        existing != null
            ? existing
            : new VpnSession(
                commonName,
                commonName,
                address,
                null,
                null,
                null,
                null,
                Instant.now());
    byVirtualIp.put(key(null, address), session);
    if (commonName != null && !commonName.isBlank()) {
      byCommonName.put(key(null, commonName), session);
    }
  }

  /** Removes the session for a common name (client-disconnect). */
  public void unregister(String commonName) {
    unregister(commonName, null);
  }

  /** Removes the session for a common name, scoped to a node when provided. */
  public void unregister(String commonName, String nodeId) {
    if (commonName == null || commonName.isBlank()) {
      return;
    }
    VpnSession removed = byCommonName.remove(key(nodeId, commonName));
    if (removed != null) {
      byVirtualIp.entrySet().removeIf(entry -> commonName.equals(entry.getValue().commonName()));
    }
  }

  /**
   * Drops every session whose common name is not in the given live set. Mirrors the {@code
   * client-disconnect} path for sessions that died with a daemon restart before the disconnect
   * callback fired; the live {@code status 3} view is the source of truth. The caller must only
   * call with a trustworthy (all-daemons-visible) view; an empty set removes everything, which is
   * correct when no client is connected.
   */
  public void retainOnly(Set<String> liveCommonNames) {
    if (liveCommonNames == null) {
      return;
    }
    byCommonName.entrySet().removeIf(e -> !liveCommonNames.contains(e.getValue().commonName()));
    byVirtualIp
        .entrySet()
        .removeIf(e -> !liveCommonNames.contains(e.getValue().commonName()));
  }

  /** Snapshot of all active sessions, ordered by connection time ascending. */
  public List<VpnSession> sessions() {
    return byCommonName.values().stream()
        .sorted(Comparator.comparing(VpnSession::connectedAt))
        .toList();
  }

  /** Number of active sessions for a username (max_connections enforcement). */
  public long countByUsername(String username) {
    if (username == null) {
      return 0;
    }
    return byCommonName.values().stream().filter(s -> username.equals(s.username())).count();
  }

  public Optional<VpnSession> byVirtualIp(String virtualIp) {
    return Optional.ofNullable(byVirtualIp.get(key(null, virtualIp)));
  }

  /** Namespaces an identity by node so equal common names/IPs can coexist across nodes. */
  private static String key(String nodeId, String value) {
    return nodeId == null ? value : nodeId + "!" + value;
  }
}
