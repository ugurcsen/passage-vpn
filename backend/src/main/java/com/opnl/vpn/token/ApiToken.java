package com.opnl.vpn.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An API token for scripted automation. Only the SHA-256 hash of the raw token is persisted; the
 * plaintext value is returned once at creation. Tokens authenticate via the {@code X-API-Token}
 * header (or a {@code Bearer} value with the {@code opnl_} prefix) and act with the stored role.
 */
@Entity
@Table(name = "api_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiToken {

  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false)
  private String label;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  /** Short, displayable prefix of the raw token (never the full value). */
  @Column(nullable = false)
  private String prefix;

  @Column(nullable = false, length = 16)
  private String role;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  public boolean expired(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }
}
