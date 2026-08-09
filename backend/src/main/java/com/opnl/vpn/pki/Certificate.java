package com.opnl.vpn.pki;

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

/** Bookkeeping for issued client certificates (PKI index.txt is the source of truth). */
@Entity
@Table(
    name = "certificates",
    indexes = @Index(name = "idx_certificates_user", columnList = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Certificate {

  public enum Status {
    VALID,
    REVOKED,
    EXPIRED
  }

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "common_name", nullable = false, unique = true, length = 128)
  private String commonName;

  @Column(name = "user_id", length = 36)
  private String userId;

  @Column(nullable = false, length = 16)
  @Enumerated(EnumType.STRING)
  private Status status;

  @Column(length = 64)
  private String serial;

  @Column(name = "issued_at")
  private Instant issuedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;
}
