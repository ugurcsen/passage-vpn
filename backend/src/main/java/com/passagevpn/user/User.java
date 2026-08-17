package com.passagevpn.user;

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

/** A panel user. Roles: ADMIN, GROUP_ADMIN, USER. */
@Entity
@Table(name = "users", indexes = @Index(name = "idx_users_username", columnList = "username"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

  public enum Role {
    ADMIN,
    GROUP_ADMIN,
    USER
  }

  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, unique = true, length = 64)
  private String username;

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "full_name", length = 128)
  private String fullName;

  @Column(length = 128)
  private String email;

  @Column(nullable = false, length = 16)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private Role role = Role.USER;

  @Column(name = "mfa_secret")
  private String mfaSecret;

  @Column(name = "mfa_enabled")
  @Builder.Default
  private boolean mfaEnabled = false;

  @Column @Builder.Default private boolean banned = false;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "failed_attempts")
  @Builder.Default
  private int failedAttempts = 0;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "static_ip", length = 45)
  private String staticIp;

  @Column(name = "static_ipv6", length = 45)
  private String staticIpv6;

  public boolean isLocked(Instant now) {
    return lockedUntil != null && now.isBefore(lockedUntil);
  }
}
