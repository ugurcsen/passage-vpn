package com.opnl.vpn.api.admin;

import com.opnl.vpn.user.User;
import java.time.Instant;
import java.util.List;

/** Admin-facing user representation. */
public record UserDto(
    String id,
    String username,
    String fullName,
    String email,
    User.Role role,
    boolean mfaEnabled,
    boolean banned,
    boolean mustChangePassword,
    List<String> groups,
    Instant createdAt,
    Instant lastLoginAt) {

  public static UserDto from(User user, boolean mustChangePassword, List<String> groupNames) {
    return new UserDto(
        user.getId(),
        user.getUsername(),
        user.getFullName(),
        user.getEmail(),
        user.getRole(),
        user.isMfaEnabled(),
        user.isBanned(),
        mustChangePassword,
        groupNames,
        user.getCreatedAt(),
        user.getLastLoginAt());
  }

  public static UserDto from(User user, boolean mustChangePassword) {
    return from(user, mustChangePassword, List.of());
  }
}
