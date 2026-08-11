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
      String remoteIp,
      String daemonName,
      Instant connectedAt) {}

  private final ConcurrentHashMap<String, VpnSession> byCommonName = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, VpnSession> byVirtualIp = new ConcurrentHashMap<>();

  /** Registers or refreshes a session after client-connect. */
  public void register(
      String username, String commonName, String virtualIp, String remoteIp, String daemonName) {
    if (commonName == null || commonName.isBlank()) {
      return;
    }
    VpnSession session =
        new VpnSession(username, commonName, virtualIp, remoteIp, daemonName, Instant.now());
    byCommonName.put(commonName, session);
    if (virtualIp != null && !virtualIp.isBlank()) {
      byVirtualIp.put(virtualIp, session);
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
    VpnSession existing = byVirtualIp.get(address);
    if ("delete".equalsIgnoreCase(operation)) {
      byVirtualIp.remove(address);
      if (existing != null) {
        byCommonName.remove(existing.commonName(), existing);
      }
      return;
    }
    VpnSession session =
        existing != null
            ? existing
            : new VpnSession(commonName, commonName, address, null, null, Instant.now());
    byVirtualIp.put(address, session);
    if (commonName != null && !commonName.isBlank()) {
      byCommonName.put(commonName, session);
    }
  }

  /** Removes the session for a common name (client-disconnect). */
  public void unregister(String commonName) {
    if (commonName == null || commonName.isBlank()) {
      return;
    }
    VpnSession removed = byCommonName.remove(commonName);
    if (removed != null && removed.virtualIp() != null) {
      byVirtualIp.remove(removed.virtualIp(), removed);
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
    byCommonName.entrySet().removeIf(entry -> !liveCommonNames.contains(entry.getKey()));
    byVirtualIp
        .entrySet()
        .removeIf(entry -> !liveCommonNames.contains(entry.getValue().commonName()));
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
    return Optional.ofNullable(byVirtualIp.get(virtualIp));
  }
}
