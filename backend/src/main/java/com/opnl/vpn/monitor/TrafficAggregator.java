package com.opnl.vpn.monitor;

import com.opnl.vpn.monitor.MgmtStatus.MgmtClientStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-session traffic accounting fed by periodic {@code status 3} polls. Computes bytes in/out per
 * second for every live session (delta between consecutive polls) and keeps a rolling ring of
 * aggregate samples for the dashboard traffic chart.
 *
 * <p>In-memory by design: history is intentionally capped (about one hour at a 5s poll interval)
 * and does not persist across restarts.
 */
@Component
public class TrafficAggregator {

  /** Current counters and computed rates for one session. */
  public record SessionTraffic(
      long bytesIn, long bytesOut, long bytesInPerSec, long bytesOutPerSec, Instant lastSeen) {}

  /** One aggregate sample for the traffic chart. */
  public record TrafficPoint(
      Instant at, long bytesInPerSec, long bytesOutPerSec, int activeConnections) {}

  private static final int MAX_POINTS = 720; // ~1 hour at a 5s poll interval
  private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(30);

  private final ConcurrentHashMap<String, SessionTraffic> sessions = new ConcurrentHashMap<>();
  private final ArrayDeque<TrafficPoint> history = new ArrayDeque<>();

  /** Applies a fresh client list (from one daemon's {@code status 3}) to the counters. */
  public void update(List<MgmtClientStatus> clients, Instant now) {
    Set<String> seen = new HashSet<>();
    for (MgmtClientStatus client : clients) {
      String commonName = client.commonName();
      if (commonName == null || commonName.isBlank()) {
        continue;
      }
      seen.add(commonName);
      SessionTraffic previous = sessions.get(commonName);
      long bytesIn = client.bytesIn();
      long bytesOut = client.bytesOut();
      long inPerSec = 0;
      long outPerSec = 0;
      if (previous != null) {
        double seconds = (now.toEpochMilli() - previous.lastSeen().toEpochMilli()) / 1000.0;
        if (seconds > 0) {
          inPerSec = Math.max(0, (long) ((bytesIn - previous.bytesIn()) / seconds));
          outPerSec = Math.max(0, (long) ((bytesOut - previous.bytesOut()) / seconds));
        }
      }
      sessions.put(commonName, new SessionTraffic(bytesIn, bytesOut, inPerSec, outPerSec, now));
    }
    // Prune sessions the daemon stopped reporting (disconnected or daemon restarted).
    sessions.entrySet().removeIf(e -> e.getValue().lastSeen().plus(SESSION_TIMEOUT).isBefore(now));

    long totalInPerSec = 0;
    long totalOutPerSec = 0;
    for (SessionTraffic traffic : sessions.values()) {
      totalInPerSec += traffic.bytesInPerSec();
      totalOutPerSec += traffic.bytesOutPerSec();
    }
    TrafficPoint point = new TrafficPoint(now, totalInPerSec, totalOutPerSec, clients.size());
    synchronized (history) {
      history.addLast(point);
      while (history.size() > MAX_POINTS) {
        history.removeFirst();
      }
    }
  }

  /** Current counters/rates for a session, or empty when never seen or already pruned. */
  public Optional<SessionTraffic> trafficFor(String commonName) {
    return Optional.ofNullable(sessions.get(commonName));
  }

  /** Last known cumulative byte counters for a session (used to finalize history rows). */
  public long[] bytesFor(String commonName) {
    SessionTraffic traffic = sessions.get(commonName);
    return traffic == null ? null : new long[] {traffic.bytesIn(), traffic.bytesOut()};
  }

  /** Snapshot of the aggregate traffic history, oldest first. */
  public List<TrafficPoint> history() {
    synchronized (history) {
      return List.copyOf(history);
    }
  }
}
