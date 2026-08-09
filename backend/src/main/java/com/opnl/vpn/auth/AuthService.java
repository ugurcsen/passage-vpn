package com.opnl.vpn.auth;

import com.opnl.vpn.auth.spi.AuthProvider;
import com.opnl.vpn.auth.spi.AuthProviderManager;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.security.JwtService;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.RefreshToken;
import com.opnl.vpn.user.RefreshTokenRepository;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Web session and VPN credential flows: login, MFA, refresh, logout, vpn verify. */
@Service
public class AuthService {

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final AuthProvider authProvider;
  private final JwtService jwtService;
  private final TotpService totpService;
  private final SettingsService settingsService;
  private final OpnlProperties properties;

  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      AuthProviderManager authProviderManager,
      JwtService jwtService,
      TotpService totpService,
      SettingsService settingsService,
      OpnlProperties properties) {
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.authProvider = authProviderManager.active();
    this.jwtService = jwtService;
    this.totpService = totpService;
    this.settingsService = settingsService;
    this.properties = properties;
  }

  public record TokenResponse(String accessToken, String refreshToken) {}

  /**
   * Result of the first login factor. When MFA is required, {@code preAuthToken} must be redeemed
   * via {@link #mfa}; otherwise access/refresh tokens are issued directly.
   */
  public record MfaChallengeResponse(
      boolean mfaRequired, String preAuthToken, String accessToken, String refreshToken) {}

  /** First login factor. Returns an MFA challenge when the account has TOTP enabled. */
  @Transactional
  public MfaChallengeResponse login(String username, String password) {
    User user = userRepository.findByUsername(username.trim()).orElse(null);
    if (user == null) {
      throw ApiException.unauthorized("invalid_credentials", "Invalid username or password");
    }
    assertAccountUsable(user, Instant.now());
    if (!authProvider.verifyCredentials(username, password)) {
      recordFailure(user);
      throw ApiException.unauthorized("invalid_credentials", "Invalid username or password");
    }
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    user.setLastLoginAt(Instant.now());
    userRepository.save(user);
    if (user.isMfaEnabled()) {
      return new MfaChallengeResponse(true, jwtService.issueMfaChallenge(user.getId()), null, null);
    }
    TokenResponse tokens = issueTokens(user);
    return new MfaChallengeResponse(false, null, tokens.accessToken(), tokens.refreshToken());
  }

  /** Second login factor: redeem the MFA challenge for a full session. */
  @Transactional
  public TokenResponse mfa(String preAuthToken, String code) {
    var claims = jwtService.parse(preAuthToken);
    if (claims == null || !jwtService.isMfaChallenge(claims)) {
      throw ApiException.unauthorized("mfa_challenge_invalid", "MFA challenge expired");
    }
    User user =
        userRepository
            .findById(claims.getSubject())
            .orElseThrow(
                () ->
                    ApiException.unauthorized(
                        "invalid_credentials", "Invalid username or password"));
    assertAccountUsable(user, Instant.now());
    if (!user.isMfaEnabled() || !totpService.verify(user.getMfaSecret(), code)) {
      throw ApiException.unauthorized("invalid_code", "Invalid or expired code");
    }
    user.setLastLoginAt(Instant.now());
    userRepository.save(user);
    return issueTokens(user);
  }

  /** Rotates a refresh token; a reused/revoked token invalidates the whole session family. */
  @Transactional
  public TokenResponse refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw ApiException.unauthorized("invalid_token", "Refresh token required");
    }
    RefreshToken stored =
        refreshTokenRepository.findByTokenHash(JwtService.hash(refreshToken)).orElse(null);
    if (stored == null || stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
      throw ApiException.unauthorized("invalid_token", "Refresh token invalid or expired");
    }
    User user = userRepository.findById(stored.getUserId()).orElse(null);
    if (user == null || user.isBanned()) {
      throw ApiException.unauthorized("invalid_token", "Account unavailable");
    }
    stored.setRevoked(true);
    refreshTokenRepository.save(stored);
    return issueTokens(user);
  }

  @Transactional
  public void logout(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }
    refreshTokenRepository
        .findByTokenHash(JwtService.hash(refreshToken))
        .ifPresent(
            t -> {
              t.setRevoked(true);
              refreshTokenRepository.save(t);
            });
  }

  /** Full user record for GET /api/auth/me. */
  @Transactional(readOnly = true)
  public User me(String userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> ApiException.unauthorized("invalid_token", "Account not found"));
  }

  // ---- VPN auth-user-pass-verify ------------------------------------------

  public record VpnVerification(boolean allowed, String reason) {}

  /**
   * Verifies a VPN connect attempt (username/password + optional TOTP). Never reveals whether the
   * account exists.
   */
  @Transactional
  public VpnVerification verifyVpnLogin(
      String username, String password, String otp, String remoteIp) {
    User user = userRepository.findByUsername(username == null ? "" : username.trim()).orElse(null);
    if (user == null || user.getPasswordHash() == null) {
      return new VpnVerification(false, "invalid_credentials");
    }
    if (user.isBanned()) {
      return new VpnVerification(false, "account_disabled");
    }
    Instant now = Instant.now();
    if (user.isLocked(now)) {
      return new VpnVerification(false, "account_locked");
    }
    if (!authProvider.verifyCredentials(username, password)) {
      recordFailure(user);
      return new VpnVerification(false, "invalid_credentials");
    }
    boolean mfaRequired =
        user.isMfaEnabled()
            || Boolean.TRUE.equals(effective(user, SettingKeys.REQUIRE_MFA_ON_CONNECT));
    if (mfaRequired && !totpService.verify(user.getMfaSecret(), otp)) {
      recordFailure(user);
      return new VpnVerification(
          false, otp == null || otp.isBlank() ? "mfa_required" : "invalid_code");
    }
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    userRepository.save(user);
    return new VpnVerification(true, null);
  }

  // ---- helpers ------------------------------------------------------------

  private TokenResponse issueTokens(User user) {
    String access =
        jwtService.issueAccessToken(user.getId(), user.getUsername(), user.getRole().name());
    String refresh = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString();
    refreshTokenRepository.save(
        RefreshToken.builder()
            .id(UUID.randomUUID().toString())
            .userId(user.getId())
            .tokenHash(JwtService.hash(refresh))
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(properties.jwt().refreshTtl()))
            .revoked(false)
            .build());
    return new TokenResponse(access, refresh);
  }

  private void assertAccountUsable(User user, Instant now) {
    if (user.isBanned()) {
      throw ApiException.forbidden("account_banned", "This account has been disabled");
    }
    if (user.isLocked(now)) {
      long remaining = user.getLockedUntil().getEpochSecond() - now.getEpochSecond();
      throw new ApiException(
          org.springframework.http.HttpStatus.UNAUTHORIZED,
          "account_locked",
          "Too many failed attempts; try again later",
          java.util.Map.of("retryAfterSeconds", remaining));
    }
  }

  private void recordFailure(User user) {
    user.setFailedAttempts(user.getFailedAttempts() + 1);
    OpnlProperties.Auth cfg = properties.auth();
    if (user.getFailedAttempts() >= cfg.lockoutMaxAttempts()) {
      user.setLockedUntil(Instant.now().plusSeconds(cfg.lockoutDurationSeconds()));
      user.setFailedAttempts(0);
    }
    userRepository.save(user);
  }

  private Object effective(User user, String key) {
    return settingsService.effectiveForUser(user.getId()).getOrDefault(key, Boolean.FALSE);
  }
}
