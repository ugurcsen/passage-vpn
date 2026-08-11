package com.opnl.vpn.monitor;

import com.opnl.vpn.config.OpnlProperties;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Owns one {@link MgmtClient} per daemon index (management socket port = {@code mgmtPort + index}).
 * Connections are established lazily on first use and transparently re-established after failures;
 * a per-daemon cooldown prevents reconnect storms while a daemon is down.
 */
@Slf4j
@Component
public class MgmtClientManager {

  private static final long RETRY_COOLDOWN_MS = 10_000;

  private final OpnlProperties properties;
  private final ConcurrentHashMap<Integer, MgmtClient> clients = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, MgmtStatus> cachedStatus = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, Long> lastAttemptAt = new ConcurrentHashMap<>();

  public MgmtClientManager(OpnlProperties properties) {
    this.properties = properties;
  }

  /**
   * Polls {@code status 3} for the given daemon, caching the result. Returns {@code null} when the
   * daemon's management socket is unreachable.
   */
  public synchronized MgmtStatus status(int daemonIndex) {
    MgmtClient client = clientFor(daemonIndex);
    if (client == null) {
      return null;
    }
    MgmtStatus status = client.status();
    if (status != null) {
      cachedStatus.put(daemonIndex, status);
    }
    return status;
  }

  /** Last successfully polled status for a daemon, or {@code null} if never reached. */
  public MgmtStatus cachedStatus(int daemonIndex) {
    return cachedStatus.get(daemonIndex);
  }

  /** Disconnects the given common name on the first daemon that acknowledges the kill. */
  public boolean kill(String commonName) {
    for (MgmtClient client : clients.values()) {
      if (client.kill(commonName)) {
        return true;
      }
    }
    return false;
  }

  private MgmtClient clientFor(int daemonIndex) {
    MgmtClient existing = clients.get(daemonIndex);
    if (existing != null && existing.isConnected()) {
      return existing;
    }
    long now = System.currentTimeMillis();
    Long last = lastAttemptAt.get(daemonIndex);
    if (last != null && now - last < RETRY_COOLDOWN_MS) {
      return null;
    }
    lastAttemptAt.put(daemonIndex, now);
    MgmtClient fresh =
        new MgmtClient(
            properties.openvpn().mgmtHost(),
            properties.openvpn().mgmtPort() + daemonIndex,
            daemonIndex);
    if (fresh.connect()) {
      clients.put(daemonIndex, fresh);
      return fresh;
    }
    return null;
  }
}
