package com.passagevpn.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A firewall access rule. Rules are evaluated per user (user-level rules, then each group's rules,
 * then global rules), sorted by priority (lower first). When any rule exists for a user, the
 * client's traffic defaults to DROP and ALLOW rules open specific flows.
 */
@Entity
@Table(
    name = "access_rules",
    indexes = @Index(name = "idx_access_rules_target", columnList = "target_type, target_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessRule {

  public enum TargetType {
    GLOBAL,
    USER,
    GROUP
  }

  public enum Action {
    ALLOW,
    DENY
  }

  public enum Protocol {
    TCP,
    UDP
  }

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "target_type", nullable = false, length = 16)
  @Enumerated(EnumType.STRING)
  private TargetType targetType;

  /** Group or user id; null for GLOBAL rules. */
  @Column(name = "target_id", length = 36)
  private String targetId;

  @Column(nullable = false, length = 8)
  @Enumerated(EnumType.STRING)
  private Action action;

  /** Null = any protocol. */
  @Column(length = 8)
  @Enumerated(EnumType.STRING)
  private Protocol protocol;

  /** Source CIDR within the VPN, e.g. 10.8.0.0/24. Null = any. */
  @Column(name = "src_cidr", length = 64)
  private String srcCidr;

  /** Destination CIDR, e.g. 10.0.0.0/8. Null = any. Mutually exclusive with dstGroupId. */
  @Column(name = "dst_cidr", length = 64)
  private String dstCidr;

  /** When set, the destination is the target group's allocated subnet rather than a CIDR. */
  @Column(name = "dst_group_id", length = 36)
  private String dstGroupId;

  /** When set, the destination is a domain name resolved to its current IPv4 addresses. */
  @Column(name = "dst_domain", length = 255)
  private String dstDomain;

  /** Destination port; null = any port. */
  @Column(name = "dst_port")
  private Integer dstPort;

  @Column(nullable = false)
  @Builder.Default
  private int priority = 100;

  @Column(nullable = false)
  @Builder.Default
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
