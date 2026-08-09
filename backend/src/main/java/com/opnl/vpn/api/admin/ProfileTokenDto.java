package com.opnl.vpn.api.admin;

import com.opnl.vpn.profile.ProfileToken;
import java.time.Instant;

/** Admin-facing profile token representation. */
public record ProfileTokenDto(
    String id,
    String token,
    String userId,
    String username,
    String profileType,
    Instant expiresAt,
    Integer usesLeft,
    Instant createdAt,
    boolean revoked) {

  public static ProfileTokenDto from(ProfileToken token, String username) {
    return new ProfileTokenDto(
        token.getId(),
        token.getToken(),
        token.getUserId(),
        username,
        token.getProfileType().name(),
        token.getExpiresAt(),
        token.getUsesLeft(),
        token.getCreatedAt(),
        token.isRevoked());
  }
}
