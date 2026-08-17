package com.passagevpn.monitor;

import com.passagevpn.api.admin.ConnectionLogDto;
import com.passagevpn.group.GroupScope;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
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
 * job prunes closed rows older than the retention window (30 days by default, configurable via the
 * {@code connection_logs_retention_days} server setting).
 *
 * <p>All persistence is best-effort: history failures must never break the VPN connect flow.
 */
@Slf4j
@Service
public class ConnectionLogService {

  private static final int DEFAULT_RETENTION_DAYS = 30;

  private final ConnectionLogRepository repository;
  private final TrafficAggregator aggregator;
  private final SettingsService settingsService;
  private final GroupScope groupScope;

  public ConnectionLogService(
      ConnectionLogRepository repository,
      TrafficAggregator aggregator,
      SettingsService settingsService,
      GroupScope groupScope) {
    this.repository = repository;
    this.aggregator = aggregator;
    this.settingsService = settingsService;
    this.groupScope = groupScope;
  }

  /** Opens a history row when a client connects (local deployment). */
  public void sessionStarted(
      String username, String commonName, String virtualIp, String remoteIp, String daemonName) {
    sessionStarted(username, commonName, virtualIp, remoteIp, daemonName, null);
  }

  /** Opens a history row when a client connects. */
  public void sessionStarted(
      String username,
      String commonName,
      String virtualIp,
      String remoteIp,
      String daemonName,
      String nodeId) {
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
              .nodeId(nodeId)
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

  /** Recent sessions, newest first, restricted to the actor's scope (ADMIN sees everything). */
  public List<ConnectionLogDto> recent(User actor, int limit) {
    int safeLimit = Math.min(Math.max(limit, 1), 100);
    PageRequest page = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "connectedAt"));
    Set<String> usernames = groupScope.scopedUsernames(actor);
    List<ConnectionLog> rows =
        usernames == null
            ? repository.findAll(page).getContent()
            : repository.findByUsernameInOrderByConnectedAtDesc(usernames, page);
    return rows.stream().map(ConnectionLogDto::from).toList();
  }

  /** Deletes closed rows older than the retention window; runs daily. */
  @Scheduled(cron = "0 15 3 * * *")
  @Transactional
  public void purgeOld() {
    try {
      Instant cutoff = Instant.now().minus(retentionDays(), ChronoUnit.DAYS);
      int removed = repository.deleteClosedBefore(cutoff);
      if (removed > 0) {
        log.info("Purged {} connection log rows older than {} days", removed, retentionDays());
      }
    } catch (Exception e) {
      log.warn("Connection log purge failed: {}", e.getMessage());
    }
  }

  /** Retention window from the server setting, clamped to 1-3650 days, defaulting to 30. */
  private long retentionDays() {
    Object raw = settingsService.serverSettings().get(SettingKeys.CONNECTION_LOGS_RETENTION_DAYS);
    if (raw instanceof Number n) {
      long days = n.longValue();
      if (days >= 1 && days <= 3650) {
        return days;
      }
    }
    return DEFAULT_RETENTION_DAYS;
  }
}
