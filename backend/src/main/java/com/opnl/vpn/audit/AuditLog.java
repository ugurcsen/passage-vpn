package com.opnl.vpn.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted audit trail entry for an admin or authentication event. Written best-effort alongside
 * the recorded mutation; pruned after the audit retention window.
 */
@Entity
@Table(
    name = "audit_logs",
    indexes = {
      @Index(name = "idx_audit_logs_created_at", columnList = "created_at"),
      @Index(name = "idx_audit_logs_actor", columnList = "actor_id"),
      @Index(name = "idx_audit_logs_action", columnList = "action")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "actor_id")
  private String actorId;

  @Column(name = "actor_name")
  private String actorName;

  @Column(nullable = false)
  private String action;

  @Column(nullable = false)
  private String category;

  @Column(name = "target_id")
  private String targetId;

  @Column(name = "target_type")
  private String targetType;

  /** Compact JSON metadata about the change; never contains secrets. */
  @Column(columnDefinition = "TEXT")
  private String detail;

  @Column(columnDefinition = "TEXT")
  private String ip;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
