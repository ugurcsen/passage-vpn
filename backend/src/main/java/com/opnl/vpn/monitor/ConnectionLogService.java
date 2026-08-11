package com.opnl.vpn.monitor;

import com.opnl.vpn.api.admin.ConnectionLogDto;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records VPN session history from the internal connect/disconnect callbacks. Session end rows are
 * finalized with the byte counters last seen by the {@link TrafficAggregator}. A daily scheduled
 * job prunes rows older than the retention window (30 days).
 *
 * <p>All persistence is best-effort: history failures must never break the VPN connect flow.
 */
@Slf4j
@Service
public class ConnectionLogService {

  private static final int RETENTION_DAYS = 30;

  private final ConnectionLogRepository repository;
  private final TrafficAggregator aggregator;

  public ConnectionLogService(ConnectionLogRepository repository, TrafficAggregator aggregator) {
    this.repository = repository;
    this.aggregator = aggregator;
  }

  /** Opens a history row when a client connects. */
  public void sessionStarted(
      String username, String commonName, String virtualIp, String remoteIp, String daemonName) {
    if (commonName == null || commonName.isBlank()) {
      return;
    }
    try {
      repository.save(
          ConnectionLog.builder()
              .id(UUID.randomUUID().toString())
              .username(username == null ? commonName : username)
              .commonName(commonName)
              .virtualIp(virtualIp)
              .remoteIp(remoteIp)
              .daemonName(daemonName)
              .connectedAt(Instant.now())
              .createdAt(Instant.now())
              .build());
    } catch (Exception e) {
      log.warn("Cannot record session start for {}: {}", commonName, e.getMessage());
    }
  }

  /** Finalizes the open history row for a client with the last known byte counters. */
  public void sessionEnded(String commonName) {
    if (commonName == null || commonName.isBlank()) {
      return;
    }
    try {
      ConnectionLog log =
          repository
              .findFirstByCommonNameAndDisconnectedAtIsNullOrderByConnectedAtDesc(commonName)
              .orElse(null);
      if (log == null) {
        return;
      }
      closeRow(log);
    } catch (Exception e) {
      log.warn("Cannot finalize session for {}: {}", commonName, e.getMessage());
    }
  }

  /**
   * Closes every open history row whose common name is no longer reported by any daemon's live
   * {@code status 3} view. Daemon restarts (SIGUSR1, crash) or container restarts can skip the
   * {@code client-disconnect} callback entirely, leaving rows with {@code disconnectedAt = null}
   * forever; the live management view is the authoritative source, so "gone from the daemon view"
   * means the session ended. An empty set is valid (no clients connected) and closes all open rows.
   */
  public void reconcileOpenSessions(java.util.Set<String> liveCommonNames) {
    if (liveCommonNames == null) {
      return;
    }
    try {
      for (ConnectionLog open : repository.findAllByDisconnectedAtIsNull()) {
        if (!liveCommonNames.contains(open.getCommonName())) {
          closeRow(open);
        }
      }
    } catch (Exception e) {
      log.warn("Cannot reconcile open sessions: {}", e.getMessage());
    }
  }

  private void closeRow(ConnectionLog log) {
    long[] bytes = aggregator.bytesFor(log.getCommonName());
    if (bytes != null) {
      log.setBytesIn(bytes[0]);
      log.setBytesOut(bytes[1]);
    }
    log.setDisconnectedAt(Instant.now());
    repository.save(log);
  }

  /** Recent sessions, newest first. */
  public List<ConnectionLogDto> recent(int limit) {
    int safeLimit = Math.min(Math.max(limit, 1), 100);
    return repository
        .findAll(PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "connectedAt")))
        .stream()
        .map(ConnectionLogDto::from)
        .toList();
  }

  /** Deletes rows older than the retention window; runs daily. */
  @Scheduled(cron = "0 15 3 * * *")
  @Transactional
  public void purgeOld() {
    try {
      Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
      int removed = repository.deleteOlderThan(cutoff);
      if (removed > 0) {
        log.info("Purged {} connection log rows older than {} days", removed, RETENTION_DAYS);
      }
    } catch (Exception e) {
      log.warn("Connection log purge failed: {}", e.getMessage());
    }
  }
}
