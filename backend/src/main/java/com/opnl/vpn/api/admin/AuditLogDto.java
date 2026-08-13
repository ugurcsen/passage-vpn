package com.opnl.vpn.api.admin;

import com.opnl.vpn.audit.AuditLog;
import java.time.Instant;

/** Admin-facing view of an audit trail entry. */
public record AuditLogDto(
    String id,
    String actorId,
    String actorName,
    String action,
    String category,
    String targetId,
    String targetType,
    String detail,
    String ip,
    Instant createdAt) {

  public static AuditLogDto from(AuditLog log) {
    return new AuditLogDto(
        log.getId(),
        log.getActorId(),
        log.getActorName(),
        log.getAction(),
        log.getCategory(),
        log.getTargetId(),
        log.getTargetType(),
        log.getDetail(),
        log.getIp(),
        log.getCreatedAt());
  }
}
