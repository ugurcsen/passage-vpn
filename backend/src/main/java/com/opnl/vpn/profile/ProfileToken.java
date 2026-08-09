package com.opnl.vpn.profile;

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

/** Time-limited or use-limited sharing token that resolves to a downloadable profile. */
@Entity
@Table(
    name = "profile_tokens",
    indexes = @Index(name = "idx_profile_tokens_token", columnList = "token", unique = true))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileToken {

  @Id
  @Column(length = 36)
  private String id;

  /** Opaque URL-safe token. */
  @Column(nullable = false, unique = true, length = 128)
  private String token;

  /** The user the profile is locked to; null for generic profiles. */
  @Column(name = "user_id", length = 36)
  private String userId;

  @Column(name = "profile_type", nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private ProfileType profileType;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "uses_left")
  private Integer usesLeft;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  @Builder.Default
  private boolean revoked = false;
}
