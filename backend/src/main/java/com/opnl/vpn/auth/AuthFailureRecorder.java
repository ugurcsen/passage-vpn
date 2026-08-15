package com.opnl.vpn.auth;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a failed authentication attempt in its own transaction. Used by flows that respond to a
 * failed attempt with a thrown {@link com.opnl.vpn.common.ApiException}: the enclosing
 * {@code @Transactional} method would otherwise roll the failure counter and the {@code
 * LOGIN_FAILED} audit row back with the exception, so lockout would never engage.
 */
@Service
public class AuthFailureRecorder {

  private final UserRepository userRepository;
  private final AuditLogService auditLogService;
  private final OpnlProperties properties;

  public AuthFailureRecorder(
      UserRepository userRepository, AuditLogService auditLogService, OpnlProperties properties) {
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
    OpnlProperties.Auth cfg = properties.auth();
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
