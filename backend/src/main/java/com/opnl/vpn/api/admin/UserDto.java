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
    boolean mfaRequired,
    boolean banned,
    boolean mustChangePassword,
    List<String> groups,
    Instant createdAt,
    Instant lastLoginAt,
    String staticIp,
    String staticIpv6) {

  public static UserDto from(
      User user, boolean mfaRequired, boolean mustChangePassword, List<String> groupNames) {
    return new UserDto(
        user.getId(),
        user.getUsername(),
        user.getFullName(),
        user.getEmail(),
        user.getRole(),
        user.isMfaEnabled(),
        mfaRequired,
        user.isBanned(),
        mustChangePassword,
        groupNames,
        user.getCreatedAt(),
        user.getLastLoginAt(),
        user.getStaticIp(),
        user.getStaticIpv6());
  }

  public static UserDto from(User user, boolean mfaRequired, boolean mustChangePassword) {
    return from(user, mfaRequired, mustChangePassword, List.of());
  }
}
