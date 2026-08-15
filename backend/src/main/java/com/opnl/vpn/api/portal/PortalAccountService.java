package com.opnl.vpn.api.portal;

import com.opnl.vpn.api.admin.UserDto;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.auth.TotpService;
import com.opnl.vpn.auth.spi.AuthProvider;
import com.opnl.vpn.auth.spi.AuthProviderManager;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.pki.CertService;
import com.opnl.vpn.pki.Certificate;
import com.opnl.vpn.pki.CertificateRepository;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.RefreshToken;
import com.opnl.vpn.user.RefreshTokenRepository;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service account operations for the client portal: TOTP MFA setup/enable/disable and password
 * changes. Sensitive operations re-verify the current password so a hijacked session cannot
 * silently take over the account.
 */
@Service
public class PortalAccountService {

  public record MfaSetup(String secret, String otpAuthUrl, String qrDataUrl) {}

  /** Snapshot of the current user's VPN certificate; {@code status} is NONE when none exists. */
  public record CertificateInfo(
      String status, String commonName, String serial, Instant issuedAt, Instant expiresAt) {}

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final TotpService totpService;
  private final SettingsService settingsService;
  private final AuthProvider authProvider;
  private final AuditLogService auditLogService;
  private final CertService certService;
  private final CertificateRepository certificateRepository;

  public PortalAccountService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordEncoder passwordEncoder,
      TotpService totpService,
      SettingsService settingsService,
      AuthProviderManager authProviderManager,
      AuditLogService auditLogService,
      CertService certService,
      CertificateRepository certificateRepository) {
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.totpService = totpService;
    this.settingsService = settingsService;
    this.authProvider = authProviderManager.active();
    this.auditLogService = auditLogService;
    this.certService = certService;
    this.certificateRepository = certificateRepository;
  }

  /** Starts TOTP provisioning; the user must confirm with {@link #enableMfa}. */
  @Transactional
  public MfaSetup setupMfa(String userId, String currentPassword) {
    User user = requireUser(userId);
    verifyPassword(user, currentPassword);
    String secret = totpService.generateSecret();
    user.setMfaSecret(secret);
    user.setMfaEnabled(false);
    userRepository.save(user);
    String uri = totpService.otpAuthUri(secret, user.getUsername());
    return new MfaSetup(secret, uri, totpService.qrPngDataUrl(secret, user.getUsername()));
  }

  /** Confirms provisioning and activates MFA for the current user. */
  @Transactional
  public UserDto enableMfa(String userId, String code) {
    User user = requireUser(userId);
    if (user.getMfaSecret() == null || !totpService.verify(user.getMfaSecret(), code)) {
      throw ApiException.badRequest("invalid_code", "Invalid code; MFA not enabled");
    }
    user.setMfaEnabled(true);
    userRepository.save(user);
    auditLogService.record(
        "MFA_ENABLE",
        AuditLogService.CAT_USER,
        user.getId(),
        "user",
        Map.of("username", user.getUsername()));
    return UserDto.from(user, mfaRequired(user), false);
  }

  /** Disables MFA after re-verifying the current password. Blocked while MFA is policy-required. */
  @Transactional
  public UserDto disableMfa(String userId, String currentPassword) {
    User user = requireUser(userId);
    assertMfaDisableAllowed(user);
    verifyPassword(user, currentPassword);
    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    userRepository.save(user);
    auditLogService.record(
        "MFA_DISABLE",
        AuditLogService.CAT_USER,
        user.getId(),
        "user",
        Map.of("username", user.getUsername()));
    return UserDto.from(user, mfaRequired(user), false);
  }

  /**
   * Changes the account password after re-verifying the current one, revokes every refresh token
   * (forcing a fresh sign-in on all devices) and clears the must-change-password flag.
   */
  @Transactional
  public void changePassword(String userId, String currentPassword, String newPassword) {
    if (newPassword == null || newPassword.length() < 8) {
      throw ApiException.badRequest("weak_password", "New password must be at least 8 characters");
    }
    User user = requireUser(userId);
    verifyPassword(user, currentPassword);
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    userRepository.save(user);
    refreshTokenRepository.findByUserId(userId).forEach(this::revoke);
    settingsService.setUserSetting(userId, SettingKeys.MUST_CHANGE_PASSWORD, false);
    auditLogService.record(
        "PASSWORD_CHANGE",
        AuditLogService.CAT_USER,
        user.getId(),
        "user",
        Map.of("username", user.getUsername()));
  }

  private void revoke(RefreshToken token) {
    token.setRevoked(true);
    refreshTokenRepository.save(token);
  }

  /** Self-service snapshot of the user's VPN certificate; NONE when no certificate exists. */
  @Transactional(readOnly = true)
  public CertificateInfo myCertificate(String userId) {
    requireUser(userId);
    return certificateRepository.findByUserId(userId).stream()
        .sorted(
            java.util.Comparator.comparing(
                Certificate::getIssuedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
        .findFirst()
        .map(PortalAccountService::toInfo)
        .orElseGet(() -> new CertificateInfo("NONE", null, null, null, null));
  }

  /**
   * Self-service rotate: revokes the current valid certificate and reissues a fresh one (same
   * common name). When no valid certificate exists a new one is issued instead.
   */
  @Transactional
  public CertificateInfo rotateCertificate(String userId) {
    requireUser(userId);
    Certificate certificate =
        certificateRepository.findByUserIdAndStatus(userId, Certificate.Status.VALID).stream()
            .findFirst()
            .map(ignored -> certService.rotate(userId))
            .orElseGet(() -> certService.ensureUserCert(userId));
    return toInfo(certificate);
  }

  private static CertificateInfo toInfo(Certificate certificate) {
    return new CertificateInfo(
        certificate.getStatus().name(),
        certificate.getCommonName(),
        certificate.getSerial(),
        certificate.getIssuedAt(),
        certificate.getExpiresAt());
  }

  private void verifyPassword(User user, String password) {
    if (password == null || !authProvider.verifyCredentials(user.getUsername(), password)) {
      throw ApiException.unauthorized("invalid_credentials", "Current password is incorrect");
    }
  }

  /** Rejects disabling MFA when the server/group policy requires it for this account. */
  private void assertMfaDisableAllowed(User user) {
    if (mfaRequired(user)) {
      throw ApiException.forbidden(
          "mfa_required", "Two-factor authentication is required by policy and cannot be disabled");
    }
  }

  /** True when the server/group policy mandates MFA for this account. */
  private boolean mfaRequired(User user) {
    Map<String, Object> settings = settingsService.effectiveForUser(user.getId());
    return settings != null && Boolean.TRUE.equals(settings.get(SettingKeys.REQUIRE_MFA));
  }

  private User requireUser(String userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> ApiException.unauthorized("unauthorized", "Authentication required"));
  }
}
