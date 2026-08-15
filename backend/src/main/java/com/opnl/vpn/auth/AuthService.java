package com.opnl.vpn.auth;

import com.opnl.vpn.api.portal.PortalAccountService;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.auth.spi.AuthProvider;
import com.opnl.vpn.auth.spi.AuthProviderManager;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.security.IpFailureTracker;
import com.opnl.vpn.security.JwtService;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.RefreshToken;
import com.opnl.vpn.user.RefreshTokenRepository;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Web session and VPN credential flows: login, MFA, refresh, logout, vpn verify. */
@Service
public class AuthService {

  /** Challenge tokens are valid for 300s (see {@link JwtService#issueMfaChallenge}). */
  private static final long MFA_CHALLENGE_TTL_SECONDS = 300;

  /** Pending OpenVPN auth-pending nonces are valid for 120s (matches the script's timeout). */
  private static final long PENDING_VPN_AUTH_TTL_SECONDS = 120;

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final AuthProvider authProvider;
  private final JwtService jwtService;
  private final TotpService totpService;
  private final SettingsService settingsService;
  private final OpnlProperties properties;
  private final AuditLogService auditLogService;
  private final PostAuthHookService postAuthHookService;
  private final IpFailureTracker ipFailureTracker;

  /** Redeemed MFA challenge ids (jti) → redemption epoch-second, for single-use enforcement. */
  private final Map<String, Long> redeemedChallenges = new ConcurrentHashMap<>();

  /** Binds an OpenVPN auth-pending phase 1 to its phase 2; single-use and short-lived. */
  private record PendingVpnAuth(String username, String remoteIp, Instant expiresAt) {}

  private final Map<String, PendingVpnAuth> pendingVpnAuths = new ConcurrentHashMap<>();

  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      AuthProviderManager authProviderManager,
      JwtService jwtService,
      TotpService totpService,
      SettingsService settingsService,
      OpnlProperties properties,
      AuditLogService auditLogService,
      PostAuthHookService postAuthHookService,
      IpFailureTracker ipFailureTracker) {
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.authProvider = authProviderManager.active();
    this.jwtService = jwtService;
    this.totpService = totpService;
    this.settingsService = settingsService;
    this.properties = properties;
    this.auditLogService = auditLogService;
    this.postAuthHookService = postAuthHookService;
    this.ipFailureTracker = ipFailureTracker;
  }

  public record TokenResponse(String accessToken, String refreshToken) {}

  /**
   * Result of the first login factor. When MFA is required, {@code preAuthToken} must be redeemed
   * via {@link #mfa}; when the account has no TOTP yet, {@code mustEnrollMfa} directs the client to
   * {@link #enrollStart}/{@link #enrollConfirm} first. Otherwise access/refresh tokens are issued
   * directly.
   */
  public record MfaChallengeResponse(
      boolean mfaRequired,
      boolean mustEnrollMfa,
      String preAuthToken,
      String accessToken,
      String refreshToken) {}

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
      auditLogService.record(
          "LOGIN_FAILED", AuditLogService.CAT_AUTH, user.getId(), "user", auditCtx(user));
      throw ApiException.unauthorized("invalid_credentials", "Invalid username or password");
    }
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    user.setLastLoginAt(Instant.now());
    userRepository.save(user);
    if (requiresMfa(user)) {
      auditLogService.record(
          "LOGIN_MFA_REQUIRED", AuditLogService.CAT_AUTH, user.getId(), "user", auditCtx(user));
      if (user.getMfaSecret() == null) {
        return new MfaChallengeResponse(
            false, true, jwtService.issueMfaEnrollChallenge(user.getId()), null, null);
      }
      return new MfaChallengeResponse(
          true, false, jwtService.issueMfaChallenge(user.getId()), null, null);
    }
    TokenResponse tokens = issueTokens(user);
    auditLogService.record(
        "LOGIN_SUCCESS", AuditLogService.CAT_AUTH, user.getId(), "user", auditCtx(user));
    return new MfaChallengeResponse(
        false, false, null, tokens.accessToken(), tokens.refreshToken());
  }

  /** Second login factor: redeem the MFA challenge for a full session. */
  @Transactional
  public TokenResponse mfa(String preAuthToken, String code) {
    var claims = jwtService.parse(preAuthToken);
    if (claims == null || !jwtService.isMfaChallenge(claims)) {
      throw ApiException.unauthorized("mfa_challenge_invalid", "MFA challenge expired");
    }
    redeemChallenge(preAuthToken, claims);
    User user =
        userRepository
            .findById(claims.getSubject())
            .orElseThrow(
                () ->
                    ApiException.unauthorized(
                        "invalid_credentials", "Invalid username or password"));
    assertAccountUsable(user, Instant.now());
    if (user.getMfaSecret() == null || !totpService.verify(user.getMfaSecret(), code)) {
      throw ApiException.unauthorized("invalid_code", "Invalid or expired code");
    }
    user.setLastLoginAt(Instant.now());
    userRepository.save(user);
    auditLogService.record(
        "LOGIN_SUCCESS", AuditLogService.CAT_AUTH, user.getId(), "user", auditCtx(user));
    return issueTokens(user);
  }

  /**
   * Starts TOTP enrollment for an account that must enable MFA before first sign-in. The secret is
   * stored (not yet active) and the QR shown; {@link #enrollConfirm} activates it.
   */
  @Transactional
  public PortalAccountService.MfaSetup enrollStart(String preAuthToken) {
    User user = requireEnrollUser(preAuthToken);
    String secret = totpService.generateSecret();
    user.setMfaSecret(secret);
    user.setMfaEnabled(false);
    userRepository.save(user);
    String uri = totpService.otpAuthUri(secret, user.getUsername());
    return new PortalAccountService.MfaSetup(
        secret, uri, totpService.qrPngDataUrl(secret, user.getUsername()));
  }

  /** Confirms TOTP enrollment and issues the final session tokens. */
  @Transactional
  public TokenResponse enrollConfirm(String preAuthToken, String code) {
    var claims = jwtService.parse(preAuthToken);
    if (claims == null || !jwtService.isMfaEnrollChallenge(claims)) {
      throw ApiException.unauthorized("mfa_challenge_invalid", "MFA challenge expired");
    }
    redeemChallenge(preAuthToken, claims);
    User user =
        userRepository
            .findById(claims.getSubject())
            .orElseThrow(
                () ->
                    ApiException.unauthorized(
                        "invalid_credentials", "Invalid username or password"));
    assertAccountUsable(user, Instant.now());
    if (user.getMfaSecret() == null || !totpService.verify(user.getMfaSecret(), code)) {
      throw ApiException.unauthorized("invalid_code", "Invalid or expired code");
    }
    user.setMfaEnabled(true);
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    user.setLastLoginAt(Instant.now());
    userRepository.save(user);
    auditLogService.record(
        "MFA_ENABLE",
        AuditLogService.CAT_USER,
        user.getId(),
        "user",
        Map.of("username", user.getUsername()));
    auditLogService.record(
        "LOGIN_SUCCESS", AuditLogService.CAT_AUTH, user.getId(), "user", auditCtx(user));
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
    auditLogService.record(
        "REFRESH_TOKEN",
        AuditLogService.CAT_AUTH,
        stored.getUserId(),
        "user",
        Map.of("username", String.valueOf(user.getUsername())));
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

  public record VpnVerification(boolean allowed, String reason, String pendingId) {
    public VpnVerification(boolean allowed, String reason) {
      this(allowed, reason, null);
    }
  }

  /**
   * Verifies a VPN connect attempt (username/password + optional TOTP). Never reveals whether the
   * account exists. When the account requires MFA and no code was supplied, a single-use {@code
   * pendingId} is issued so phase 2 ({@link #verifyVpnOtp}) can be bound to this attempt.
   */
  @Transactional
  public VpnVerification verifyVpnLogin(
      String username, String password, String otp, String remoteIp) {
    if (ipFailureTracker.isBlocked(remoteIp)) {
      return new VpnVerification(false, "ip_blocked");
    }
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
      ipFailureTracker.recordFailure(remoteIp);
      return new VpnVerification(false, "invalid_credentials");
    }
    boolean mfaRequired =
        requiresMfa(user)
            || Boolean.TRUE.equals(effective(user, SettingKeys.REQUIRE_MFA_ON_CONNECT));
    if (mfaRequired) {
      if (user.getMfaSecret() == null) {
        return new VpnVerification(false, "mfa_required");
      }
      if (otp == null || otp.isBlank()) {
        String pendingId = createPendingVpnAuth(user.getUsername(), remoteIp);
        return new VpnVerification(false, "mfa_required", pendingId);
      }
      if (!totpService.verify(user.getMfaSecret(), otp)) {
        recordFailure(user);
        ipFailureTracker.recordFailure(remoteIp);
        return new VpnVerification(false, "invalid_code");
      }
    }
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    userRepository.save(user);
    ipFailureTracker.reset(remoteIp);
    postAuthHookService.run(username, remoteIp);
    return new VpnVerification(true, null);
  }

  /**
   * Verifies the second factor (TOTP) of an OpenVPN auth-pending session (client-crresponse). The
   * password has already been accepted by {@link #verifyVpnLogin}; only the code is checked here.
   * The {@code pendingId} issued in phase 1 must be presented and is consumed (fail-closed).
   */
  @Transactional
  public VpnVerification verifyVpnOtp(
      String username, String otp, String remoteIp, String pendingId) {
    if (ipFailureTracker.isBlocked(remoteIp)) {
      return new VpnVerification(false, "ip_blocked");
    }
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
    if (!consumePendingVpnAuth(pendingId, user.getUsername())) {
      recordFailure(user);
      ipFailureTracker.recordFailure(remoteIp);
      return new VpnVerification(false, "missing_pending");
    }
    boolean mfaRequired =
        requiresMfa(user)
            || Boolean.TRUE.equals(effective(user, SettingKeys.REQUIRE_MFA_ON_CONNECT));
    if (!mfaRequired) {
      return new VpnVerification(false, "mfa_not_required");
    }
    if (user.getMfaSecret() == null
        || otp == null
        || otp.isBlank()
        || !totpService.verify(user.getMfaSecret(), otp)) {
      recordFailure(user);
      ipFailureTracker.recordFailure(remoteIp);
      return new VpnVerification(false, "invalid_code");
    }
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    userRepository.save(user);
    ipFailureTracker.reset(remoteIp);
    postAuthHookService.run(username, remoteIp);
    return new VpnVerification(true, null);
  }

  // ---- helpers ------------------------------------------------------------

  private void pruneExpiredChallenges(long now) {
    redeemedChallenges.entrySet().removeIf(e -> now - e.getValue() > MFA_CHALLENGE_TTL_SECONDS);
  }

  /** Creates a single-use nonce binding a phase-1 VPN auth to its TOTP phase 2. */
  private String createPendingVpnAuth(String username, String remoteIp) {
    prunePendingVpnAuths();
    String pendingId = UUID.randomUUID().toString().replace("-", "");
    pendingVpnAuths.put(
        pendingId,
        new PendingVpnAuth(
            username, remoteIp, Instant.now().plusSeconds(PENDING_VPN_AUTH_TTL_SECONDS)));
    return pendingId;
  }

  /** Consumes the nonce when it matches the username and is still valid; fail-closed otherwise. */
  private boolean consumePendingVpnAuth(String pendingId, String username) {
    prunePendingVpnAuths();
    if (pendingId == null || pendingId.isBlank()) {
      return false;
    }
    PendingVpnAuth pending = pendingVpnAuths.remove(pendingId);
    if (pending == null
        || !pending.username().equals(username)
        || pending.expiresAt().isBefore(Instant.now())) {
      return false;
    }
    return true;
  }

  private void prunePendingVpnAuths() {
    Instant cutoff = Instant.now().minusSeconds(PENDING_VPN_AUTH_TTL_SECONDS);
    pendingVpnAuths.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(cutoff));
  }

  /** Marks a pre-auth challenge single-use; concurrent/replayed use is rejected. */
  private void redeemChallenge(String preAuthToken, io.jsonwebtoken.Claims claims) {
    long now = Instant.now().getEpochSecond();
    pruneExpiredChallenges(now);
    String challengeKey = claims.getId() != null ? claims.getId() : JwtService.hash(preAuthToken);
    if (redeemedChallenges.putIfAbsent(challengeKey, now) != null) {
      throw ApiException.unauthorized("mfa_challenge_invalid", "MFA challenge already used");
    }
  }

  /** Resolves the account behind a valid MFA enrollment challenge. */
  private User requireEnrollUser(String preAuthToken) {
    var claims = jwtService.parse(preAuthToken);
    if (claims == null || !jwtService.isMfaEnrollChallenge(claims)) {
      throw ApiException.unauthorized("mfa_challenge_invalid", "MFA challenge expired");
    }
    return userRepository
        .findById(claims.getSubject())
        .orElseThrow(
            () -> ApiException.unauthorized("invalid_credentials", "Invalid username or password"));
  }

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
    Map<String, Object> map = settingsService.effectiveForUser(user.getId());
    return map == null ? null : map.get(key);
  }

  /** True when the account must present a TOTP code at login (self-enabled or policy-mandated). */
  private boolean requiresMfa(User user) {
    return user.isMfaEnabled() || Boolean.TRUE.equals(effective(user, SettingKeys.REQUIRE_MFA));
  }

  private String currentRemoteIp() {
    var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
      var req = sra.getRequest();
      return req.getRemoteAddr();
    }
    return null;
  }

  private Map<String, Object> auditCtx(User user) {
    Map<String, Object> ctx = new java.util.HashMap<>();
    ctx.put("username", user.getUsername());
    String ip = currentRemoteIp();
    if (ip != null) {
      ctx.put("remoteIp", ip);
    }
    return ctx;
  }
}
