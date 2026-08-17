package com.passagevpn.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.api.admin.AuditLogDto;
import com.passagevpn.api.admin.PageDto;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.syslog.SyslogService;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records audit events for admin mutations, portal self-service changes and authentication flows.
 * Persistence is best-effort (a failed write must never break the recorded operation) and every
 * stored event is also forwarded to syslog when the {@code syslog_enabled} setting is on. A daily
 * scheduled job prunes entries older than the audit retention window (90 days by default).
 */
@Slf4j
@Service
public class AuditLogService {

  private static final int DEFAULT_RETENTION_DAYS = 90;

  // categories
  public static final String CAT_USER = "USER";
  public static final String CAT_GROUP = "GROUP";
  public static final String CAT_RULE = "RULE";
  public static final String CAT_CERT = "CERT";
  public static final String CAT_DAEMON = "DAEMON";
  public static final String CAT_SETTING = "SETTING";
  public static final String CAT_AUTH = "AUTH";
  public static final String CAT_PORTAL = "PORTAL";
  public static final String CAT_API = "API";
  public static final String CAT_BACKUP = "BACKUP";
  public static final String CAT_DNS = "DNS";
  public static final String CAT_NODE = "NODE";

  public static final String CAT_SYSTEM = "SYSTEM";

  private final AuditLogRepository repository;
  private final SettingsService settingsService;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;
  private final SyslogService syslogService;

  public AuditLogService(
      AuditLogRepository repository,
      SettingsService settingsService,
      UserRepository userRepository,
      ObjectMapper objectMapper,
      SyslogService syslogService) {
    this.repository = repository;
    this.settingsService = settingsService;
    this.userRepository = userRepository;
    this.objectMapper = objectMapper;
    this.syslogService = syslogService;
  }

  /** Records an event attributing it to the currently authenticated user and request. */
  public void record(
      String action,
      String category,
      String targetId,
      String targetType,
      Map<String, Object> detail) {
    Actor actor = currentActor();
    record(actor.id(), actor.name(), action, category, targetId, targetType, detail, requestIp());
  }

  /** Records an event with an explicit actor (used for anonymous flows like login). */
  public void record(
      String actorId,
      String actorName,
      String action,
      String category,
      String targetId,
      String targetType,
      Map<String, Object> detail,
      String ip) {
    try {
      AuditLog entry =
          AuditLog.builder()
              .id(UUID.randomUUID().toString())
              .actorId(actorId)
              .actorName(actorName)
              .action(action)
              .category(category)
              .targetId(targetId)
              .targetType(targetType)
              .detail(toJson(detail))
              .ip(ip)
              .createdAt(Instant.now())
              .build();
      repository.save(entry);
      emitSyslog(entry);
    } catch (Exception e) {
      log.warn("Cannot record audit event {}: {}", action, e.getMessage());
    }
  }

  /** Filtered, paginated audit trail, newest first. */
  @Transactional(readOnly = true)
  public PageDto<AuditLogDto> search(
      int page, int size, String action, String actor, Instant from, Instant to) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 100);
    String actionPattern = wildcard(action);
    String actorNamePattern = wildcard(actor);
    Page<AuditLog> result =
        repository.search(
            actionPattern,
            actor == null || actor.isBlank() ? null : actor.trim(),
            actorNamePattern,
            from,
            to,
            PageRequest.of(safePage, safeSize));
    return PageDto.of(result.map(AuditLogDto::from));
  }

  /** Deletes audit entries older than the retention window; runs daily. */
  @Scheduled(cron = "0 15 3 * * *")
  @Transactional
  public void purgeOld() {
    try {
      Instant cutoff = Instant.now().minus(retentionDays(), ChronoUnit.DAYS);
      int removed = repository.deleteOlderThan(cutoff);
      if (removed > 0) {
        log.info("Purged {} audit log rows older than {} days", removed, retentionDays());
      }
    } catch (Exception e) {
      log.warn("Audit log purge failed: {}", e.getMessage());
    }
  }

  // ---- helpers ------------------------------------------------------------

  private void emitSyslog(AuditLog entry) {
    String facility = CAT_AUTH.equals(entry.getCategory()) ? "auth" : "local0";
    String message =
        "category="
            + entry.getCategory()
            + " action="
            + entry.getAction()
            + " actor="
            + (entry.getActorName() == null ? "-" : entry.getActorName())
            + (entry.getActorId() == null ? "" : " actorId=" + entry.getActorId())
            + " target="
            + (entry.getTargetId() == null
                ? "-"
                : entry.getTargetType() + "/" + entry.getTargetId())
            + " ip="
            + (entry.getIp() == null ? "-" : entry.getIp())
            + (entry.getDetail() == null ? "" : " detail=" + entry.getDetail());
    syslogService.emit(facility, message);
  }

  private String toJson(Map<String, Object> detail) {
    if (detail == null || detail.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(detail);
    } catch (Exception e) {
      return null;
    }
  }

  private long retentionDays() {
    Object raw = settingsService.serverSettings().get(SettingKeys.AUDIT_LOGS_RETENTION_DAYS);
    if (raw instanceof Number n) {
      long days = n.longValue();
      if (days >= 1 && days <= 3650) {
        return days;
      }
    }
    return DEFAULT_RETENTION_DAYS;
  }

  private static String wildcard(String term) {
    if (term == null || term.isBlank()) {
      return null;
    }
    return "%" + term.trim().toLowerCase() + "%";
  }

  private Actor currentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof String userId)) {
      return new Actor(null, null);
    }
    return userRepository
        .findById(userId)
        .map(u -> new Actor(u.getId(), u.getUsername()))
        .orElse(new Actor(null, null));
  }

  private static String requestIp() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
        && attrs.getRequest() != null) {
      return attrs.getRequest().getRemoteAddr();
    }
    return null;
  }

  private record Actor(String id, String name) {}
}
