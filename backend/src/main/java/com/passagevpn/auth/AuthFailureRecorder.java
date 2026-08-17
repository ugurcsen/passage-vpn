package com.passagevpn.auth;

import com.passagevpn.audit.AuditLogService;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a failed authentication attempt in its own transaction. Used by flows that respond to a
 * failed attempt with a thrown {@link com.passagevpn.common.ApiException}: the enclosing
 * {@code @Transactional} method would otherwise roll the failure counter and the {@code
 * LOGIN_FAILED} audit row back with the exception, so lockout would never engage.
 */
@Service
public class AuthFailureRecorder {

  private final UserRepository userRepository;
  private final AuditLogService auditLogService;
  private final PassageProperties properties;

  public AuthFailureRecorder(
      UserRepository userRepository,
      AuditLogService auditLogService,
      PassageProperties properties) {
    this.userRepository = userRepository;
    this.auditLogService = auditLogService;
    this.properties = properties;
  }

  /** Records the failure for the given user and writes the {@code LOGIN_FAILED} audit entry. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(String userId, String username, String remoteIp) {
    User user = userRepository.findById(userId).orElse(null);
    if (user == null) {
      return;
    }
    user.setFailedAttempts(user.getFailedAttempts() + 1);
    PassageProperties.Auth cfg = properties.auth();
    if (user.getFailedAttempts() >= cfg.lockoutMaxAttempts()) {
      user.setLockedUntil(Instant.now().plusSeconds(cfg.lockoutDurationSeconds()));
      user.setFailedAttempts(0);
    }
    userRepository.save(user);
    auditLogService.record(
        "LOGIN_FAILED",
        AuditLogService.CAT_AUTH,
        user.getId(),
        "user",
        Map.of("username", username, "remoteIp", remoteIp == null ? "" : remoteIp));
  }
}
