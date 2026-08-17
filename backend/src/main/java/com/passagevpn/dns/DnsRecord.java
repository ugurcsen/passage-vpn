package com.passagevpn.dns;

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
 * A DNS override: a hostname the container's dnsmasq answers authoritatively for VPN clients. The
 * shared resolver serves one answer per hostname, so hostnames are unique and scopes only control
 * which users may reach the address (enforced per-client by the rule engine).
 */
@Entity
@Table(
    name = "dns_records",
    indexes = @Index(name = "idx_dns_records_scope", columnList = "scope, scope_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DnsRecord {

  public enum Scope {
    GLOBAL,
    GROUP,
    USER
  }

  @Id
  @Column(length = 36)
  private String id;

  /** Lowercased hostname, e.g. {@code git.internal}. */
  @Column(nullable = false, unique = true, length = 253)
  private String hostname;

  /** The IPv4 address dnsmasq returns for {@link #hostname}. */
  @Column(nullable = false, length = 45)
  private String ipv4;

  /**
   * Optional IPv6 answer dnsmasq returns for {@link #hostname}; null/blank disables the AAAA pin.
   */
  @Column(length = 45)
  private String ipv6;

  @Column(nullable = false, length = 8)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private Scope scope = Scope.GLOBAL;

  /** Group or user id; null for GLOBAL-scoped records. */
  @Column(name = "scope_id", length = 36)
  private String scopeId;

  @Column(nullable = false)
  @Builder.Default
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
