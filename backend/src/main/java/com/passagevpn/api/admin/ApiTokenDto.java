package com.passagevpn.api.admin;

import com.passagevpn.token.ApiToken;
import java.time.Instant;

/** List/management view of an API token; never contains the raw token value. */
public record ApiTokenDto(
    String id,
    String label,
    String prefix,
    String role,
    Instant expiresAt,
    Instant createdAt,
    Instant lastUsedAt,
    String createdBy) {

  public static ApiTokenDto from(ApiToken token) {
    return new ApiTokenDto(
        token.getId(),
        token.getLabel(),
        token.getPrefix() + "…",
        token.getRole(),
        token.getExpiresAt(),
        token.getCreatedAt(),
        token.getLastUsedAt(),
        token.getCreatedBy());
  }
}
