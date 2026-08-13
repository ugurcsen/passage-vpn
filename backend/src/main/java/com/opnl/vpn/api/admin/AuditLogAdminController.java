package com.opnl.vpn.api.admin;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin audit trail: paginated, filterable log of admin and auth events, newest first. */
@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Audit Logs", description = "Filterable audit trail (admin-only)")
public class AuditLogAdminController {

  private final AuditLogService auditLogService;

  public AuditLogAdminController(AuditLogService auditLogService) {
    this.auditLogService = auditLogService;
  }

  @GetMapping
  public PageDto<AuditLogDto> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return auditLogService.search(
        page, size, action, actor, parseRange(from, false), parseRange(to, true));
  }

  /**
   * Accepts a full ISO instant or a plain {@code yyyy-MM-dd} date. {@code endOfDay} snaps a date to
   * the last millisecond of that day so date-only {@code to} filters stay inclusive.
   */
  private static Instant parseRange(String raw, boolean endOfDay) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (Exception ignored) {
      // fall through to date-only parsing
    }
    try {
      LocalDate date = LocalDate.parse(raw.trim());
      return endOfDay
          ? date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)
          : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    } catch (Exception e) {
      throw ApiException.badRequest("invalid_date", "Invalid date filter; use yyyy-MM-dd or ISO");
    }
  }
}
