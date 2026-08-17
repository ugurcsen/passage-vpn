package com.passagevpn.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.audit.AuditLogService;
import com.passagevpn.auth.spi.AuthProviderManager;
import com.passagevpn.auth.spi.LocalAuthProvider;
import com.passagevpn.common.ApiException;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.security.IpFailureTracker;
import com.passagevpn.security.JwtService;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.RefreshToken;
import com.passagevpn.user.RefreshTokenRepository;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

  private static final String SECRET = "test-jwt-secret-at-least-32-bytes-long";

  private UserRepository userRepository;
  private RefreshTokenRepository refreshTokenRepository;
  private SettingsService settingsService;
  private PassageProperties properties;
  private AuthService service;
  private JwtService jwtService;
  private BCryptPasswordEncoder encoder;
  private PostAuthHookService postAuthHookService;
  private IpFailureTracker ipFailureTracker;
  private AuthFailureRecorder authFailureRecorder;
  private AuditLogService auditLogService;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    refreshTokenRepository = mock(RefreshTokenRepository.class);
    settingsService = mock(SettingsService.class);
    properties = mock(PassageProperties.class);
    PassageProperties.Jwt jwt = mock(PassageProperties.Jwt.class);
    when(jwt.secret()).thenReturn(SECRET);
    when(jwt.accessTtl()).thenReturn(Duration.ofMinutes(15));
    when(jwt.refreshTtl()).thenReturn(Duration.ofDays(14));
    when(properties.jwt()).thenReturn(jwt);
    PassageProperties.Auth auth = mock(PassageProperties.Auth.class);
    when(auth.provider()).thenReturn("local");
    when(auth.lockoutMaxAttempts()).thenReturn(3);
    when(auth.lockoutWindowSeconds()).thenReturn(300);
    when(auth.lockoutDurationSeconds()).thenReturn(300);
    when(properties.auth()).thenReturn(auth);
    jwtService = new JwtService(properties);
    encoder = new BCryptPasswordEncoder();
    AuthProviderManager authProviderManager =
        new AuthProviderManager(
            properties, List.of(new LocalAuthProvider(userRepository, encoder)));
    postAuthHookService = mock(PostAuthHookService.class);
    ipFailureTracker = mock(IpFailureTracker.class);
    auditLogService = mock(AuditLogService.class);
    authFailureRecorder = new AuthFailureRecorder(userRepository, auditLogService, properties);
    service =
        new AuthService(
            userRepository,
            refreshTokenRepository,
            authProviderManager,
            jwtService,
            new TotpService(),
            settingsService,
            properties,
            auditLogService,
            postAuthHookService,
            ipFailureTracker,
            authFailureRecorder);
  }

  private User user(String username, boolean mfaEnabled, String mfaSecret) {
    return User.builder()
        .id("u1")
        .username(username)
        .passwordHash(encoder.encode("supersecret1"))
        .role(User.Role.USER)
        .mfaEnabled(mfaEnabled)
        .mfaSecret(mfaSecret)
        .createdAt(Instant.now())
        .build();
  }

  @Test
  void loginWithoutMfaReturnsTokensAndNoChallenge() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    var result = service.login("alice", "supersecret1");
    assertThat(result.mfaRequired()).isFalse();
    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank();
  }

  @Test
  void loginWithMfaReturnsChallenge() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, "JBSWY3DPEHPK3PXP")));
    var result = service.login("alice", "supersecret1");
    assertThat(result.mfaRequired()).isTrue();
    assertThat(result.mustEnrollMfa()).isFalse();
    assertThat(result.preAuthToken()).isNotBlank();
    assertThat(result.accessToken()).isNull();
  }

  @Test
  void loginWithoutSecretButPolicyRequiresMfaForcesEnrollment() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString()))
        .thenReturn(Map.of(SettingKeys.REQUIRE_MFA, true));
    var result = service.login("alice", "supersecret1");
    assertThat(result.mfaRequired()).isFalse();
    assertThat(result.mustEnrollMfa()).isTrue();
    assertThat(result.preAuthToken()).isNotBlank();
    assertThat(result.accessToken()).isNull();
  }

  @Test
  void enrollStartStoresSecretAndReturnsQr() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString()))
        .thenReturn(Map.of(SettingKeys.REQUIRE_MFA, true));
    var login = service.login("alice", "supersecret1");
    assertThat(login.mustEnrollMfa()).isTrue();

    when(userRepository.findById("u1")).thenReturn(Optional.of(user("alice", false, null)));
    var setup = service.enrollStart(login.preAuthToken());
    assertThat(setup.secret()).isNotBlank();
    assertThat(setup.otpAuthUrl()).startsWith("otpauth://totp/");
    assertThat(setup.qrDataUrl()).startsWith("data:image/png;base64,");
  }

  @Test
  void enrollConfirmActivatesMfaAndIssuesTokens() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString()))
        .thenReturn(Map.of(SettingKeys.REQUIRE_MFA, true));
    var login = service.login("alice", "supersecret1");
    assertThat(login.mustEnrollMfa()).isTrue();

    User withSecret = user("alice", false, null);
    withSecret.setMfaSecret(new TotpService().generateSecret());
    when(userRepository.findById("u1")).thenReturn(Optional.of(withSecret));

    var tokens = service.enrollConfirm(login.preAuthToken(), totpCode(withSecret.getMfaSecret()));
    assertThat(tokens.accessToken()).isNotBlank();
    assertThat(tokens.refreshToken()).isNotBlank();
    assertThat(withSecret.isMfaEnabled()).isTrue();
  }

  @Test
  void enrollConfirmRejectsInvalidCode() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString()))
        .thenReturn(Map.of(SettingKeys.REQUIRE_MFA, true));
    var login = service.login("alice", "supersecret1");

    User withSecret = user("alice", false, null);
    withSecret.setMfaSecret(new TotpService().generateSecret());
    when(userRepository.findById("u1")).thenReturn(Optional.of(withSecret));

    assertThatThrownBy(() -> service.enrollConfirm(login.preAuthToken(), "000000"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_code"));
  }

  @Test
  void enrollChallengeIsSingleUse() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString()))
        .thenReturn(Map.of(SettingKeys.REQUIRE_MFA, true));
    var login = service.login("alice", "supersecret1");

    User withSecret = user("alice", false, null);
    withSecret.setMfaSecret(new TotpService().generateSecret());
    when(userRepository.findById("u1")).thenReturn(Optional.of(withSecret));

    service.enrollConfirm(login.preAuthToken(), totpCode(withSecret.getMfaSecret()));
    assertThatThrownBy(
            () -> service.enrollConfirm(login.preAuthToken(), totpCode(withSecret.getMfaSecret())))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> assertThat(((ApiException) e).getCode()).isEqualTo("mfa_challenge_invalid"));
  }

  @Test
  void mfaAcceptsProvisionedButNotYetEnabledSecret() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, secret)));
    when(settingsService.effectiveForUser(anyString()))
        .thenReturn(Map.of(SettingKeys.REQUIRE_MFA, true));
    var challenge = service.login("alice", "supersecret1");
    assertThat(challenge.mfaRequired()).isTrue();

    when(userRepository.findById("u1")).thenReturn(Optional.of(user("alice", false, secret)));
    var tokens = service.mfa(challenge.preAuthToken(), totpCode(secret));
    assertThat(tokens.accessToken()).isNotBlank();
  }

  @Test
  void loginRejectsWrongPassword() {
    User alice = user("alice", false, null);
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    assertThatThrownBy(() -> service.login("alice", "wrongpass1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_credentials"));
    assertThat(alice.getFailedAttempts()).isEqualTo(1);
    verify(userRepository).save(any());
    verify(auditLogService)
        .record(eq("LOGIN_FAILED"), eq(AuditLogService.CAT_AUTH), eq("u1"), eq("user"), anyMap());
  }

  @Test
  void loginLocksAccountAfterMaxAttempts() {
    User alice = user("alice", false, null);
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> service.login("alice", "wrongpass1"))
          .isInstanceOf(ApiException.class);
    }
    assertThat(alice.getLockedUntil()).isNotNull();
    assertThat(alice.getFailedAttempts()).isZero();
    assertThatThrownBy(() -> service.login("alice", "supersecret1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("account_locked"));
  }

  @Test
  void refreshRotatesTokenAndRevokesOld() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, secret)));
    var challenge = service.login("alice", "supersecret1");
    String code;
    try {
      code =
          new dev.samstevens.totp.code.DefaultCodeGenerator(
                  dev.samstevens.totp.code.HashingAlgorithm.SHA1, 6)
              .generate(secret, new dev.samstevens.totp.time.SystemTimeProvider().getTime() / 30);
    } catch (dev.samstevens.totp.exceptions.CodeGenerationException e) {
      throw new RuntimeException(e);
    }
    when(userRepository.findById("u1")).thenReturn(Optional.of(user("alice", true, secret)));
    var tokens = service.mfa(challenge.preAuthToken(), code);

    RefreshToken stored =
        RefreshToken.builder()
            .userId("u1")
            .tokenHash(JwtService.hash(tokens.refreshToken()))
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(Duration.ofDays(14)))
            .build();
    when(refreshTokenRepository.findByTokenHash(JwtService.hash(tokens.refreshToken())))
        .thenReturn(Optional.of(stored));
    when(userRepository.findById("u1")).thenReturn(Optional.of(user("alice", true, secret)));

    var rotated = service.refresh(tokens.refreshToken());
    assertThat(rotated.accessToken()).isNotBlank();
    assertThat(rotated.refreshToken()).isNotEqualTo(tokens.refreshToken());
    assertThat(stored.isRevoked()).isTrue();
  }

  @Test
  void mfaChallengeIsSingleUse() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, secret)));
    var challenge = service.login("alice", "supersecret1");
    assertThat(challenge.mfaRequired()).isTrue();

    when(userRepository.findById("u1")).thenReturn(Optional.of(user("alice", true, secret)));
    var first = service.mfa(challenge.preAuthToken(), totpCode(secret));
    assertThat(first.accessToken()).isNotBlank();

    assertThatThrownBy(() -> service.mfa(challenge.preAuthToken(), totpCode(secret)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> assertThat(((ApiException) e).getCode()).isEqualTo("mfa_challenge_invalid"));
  }

  @Test
  void mfaRejectsWrongCodeAndRecordsFailure() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, secret)));
    var challenge = service.login("alice", "supersecret1");
    assertThat(challenge.mfaRequired()).isTrue();

    User alice = user("alice", true, secret);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    assertThatThrownBy(() -> service.mfa(challenge.preAuthToken(), "000000"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_code"));
    assertThat(alice.getFailedAttempts()).isEqualTo(1);
    verify(auditLogService)
        .record(eq("LOGIN_FAILED"), eq(AuditLogService.CAT_AUTH), eq("u1"), eq("user"), anyMap());
  }

  @Test
  void refreshRejectsRevokedToken() {
    RefreshToken revoked =
        RefreshToken.builder()
            .userId("u1")
            .tokenHash("hash")
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(Duration.ofDays(14)))
            .revoked(true)
            .build();
    when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));
    assertThatThrownBy(() -> service.refresh("some-token"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_token"));
  }

  @Test
  void vpnVerifyAllowsValidPasswordWithoutMfa() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString())).thenReturn(Map.of());
    var result = service.verifyVpnLogin("alice", "supersecret1", null, "1.2.3.4");
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void vpnVerifyRejectsDisabledAccount() {
    User banned = user("bob", false, null);
    banned.setBanned(true);
    when(userRepository.findByUsername("bob")).thenReturn(Optional.of(banned));
    var result = service.verifyVpnLogin("bob", "supersecret1", null, "1.2.3.4");
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("account_disabled");
  }

  @Test
  void refreshRejectsBannedAccount() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    var challenge = service.login("alice", "supersecret1");
    when(refreshTokenRepository.findByTokenHash(JwtService.hash(challenge.refreshToken())))
        .thenReturn(
            Optional.of(
                RefreshToken.builder()
                    .userId("u1")
                    .tokenHash(JwtService.hash(challenge.refreshToken()))
                    .createdAt(Instant.now())
                    .expiresAt(Instant.now().plus(Duration.ofDays(14)))
                    .build()));
    User banned = user("alice", false, null);
    banned.setBanned(true);
    when(userRepository.findById("u1")).thenReturn(Optional.of(banned));

    assertThatThrownBy(() -> service.refresh(challenge.refreshToken()))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_token"));
  }

  @Test
  void refreshRejectsExpiredToken() {
    RefreshToken expired =
        RefreshToken.builder()
            .userId("u1")
            .tokenHash("hash")
            .createdAt(Instant.now().minus(Duration.ofDays(2)))
            .expiresAt(Instant.now().minus(Duration.ofDays(1)))
            .build();
    when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));
    assertThatThrownBy(() -> service.refresh("some-token"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_token"));
  }

  @Test
  void vpnVerifyRequiresMfaWhenSettingEnforces() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString()))
        .thenReturn(Map.of(SettingKeys.REQUIRE_MFA_ON_CONNECT, true));
    var withoutOtp = service.verifyVpnLogin("alice", "supersecret1", null, "1.2.3.4");
    assertThat(withoutOtp.allowed()).isFalse();
    assertThat(withoutOtp.reason()).isEqualTo("mfa_required");
  }

  @Test
  void vpnVerifyDeniedWhenMfaRequiredButNotEnrolled() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString()))
        .thenReturn(Map.of(SettingKeys.REQUIRE_MFA, true));
    var result = service.verifyVpnLogin("alice", "supersecret1", "123456", "1.2.3.4");
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("mfa_required");
  }

  @Test
  void vpnVerifyOtpRejectsMissingPending() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, "JBSWY3DPEHPK3PXP")));
    var result = service.verifyVpnOtp("alice", "123456", "1.2.3.4", "no-such-pending");
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("missing_pending");
  }

  @Test
  void vpnVerifyRejectsWrongTotpWhenMfaRequired() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, "JBSWY3DPEHPK3PXP")));
    var wrongOtp = service.verifyVpnLogin("alice", "supersecret1", "000000", "1.2.3.4");
    assertThat(wrongOtp.allowed()).isFalse();
    assertThat(wrongOtp.reason()).isEqualTo("invalid_code");
  }

  @Test
  void vpnVerifyAllowsValidTotpWhenMfaRequired() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, secret)));
    String code;
    try {
      code =
          new dev.samstevens.totp.code.DefaultCodeGenerator(
                  dev.samstevens.totp.code.HashingAlgorithm.SHA1, 6)
              .generate(secret, new dev.samstevens.totp.time.SystemTimeProvider().getTime() / 30);
    } catch (dev.samstevens.totp.exceptions.CodeGenerationException e) {
      throw new RuntimeException(e);
    }
    var result = service.verifyVpnLogin("alice", "supersecret1", code, "1.2.3.4");
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void vpnVerifyRejectsLockedAccount() {
    User locked = user("carol", false, null);
    locked.setLockedUntil(Instant.now().plusSeconds(300));
    when(userRepository.findByUsername("carol")).thenReturn(Optional.of(locked));
    var result = service.verifyVpnLogin("carol", "supersecret1", null, "1.2.3.4");
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("account_locked");
  }

  @Test
  void vpnVerifyOtpAllowsValidCode() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, secret)));
    var phaseOne = service.verifyVpnLogin("alice", "supersecret1", null, "1.2.3.4");
    assertThat(phaseOne.reason()).isEqualTo("mfa_required");
    assertThat(phaseOne.pendingId()).isNotBlank();
    var result = service.verifyVpnOtp("alice", totpCode(secret), "1.2.3.4", phaseOne.pendingId());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void vpnVerifyOtpRejectsInvalidCodeAndRecordsFailure() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, secret)));
    var phaseOne = service.verifyVpnLogin("alice", "supersecret1", null, "1.2.3.4");
    var result = service.verifyVpnOtp("alice", "000000", "1.2.3.4", phaseOne.pendingId());
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("invalid_code");
    verify(userRepository).save(any());
  }

  @Test
  void vpnVerifyOtpRejectsUnknownUser() {
    when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
    var result = service.verifyVpnOtp("ghost", "123456", "1.2.3.4", "abc");
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("invalid_credentials");
  }

  @Test
  void vpnVerifyOtpRejectsReusedPending() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, secret)));
    var phaseOne = service.verifyVpnLogin("alice", "supersecret1", null, "1.2.3.4");
    var first = service.verifyVpnOtp("alice", totpCode(secret), "1.2.3.4", phaseOne.pendingId());
    assertThat(first.allowed()).isTrue();
    var replay = service.verifyVpnOtp("alice", totpCode(secret), "1.2.3.4", phaseOne.pendingId());
    assertThat(replay.allowed()).isFalse();
    assertThat(replay.reason()).isEqualTo("missing_pending");
  }

  @Test
  void vpnVerifyOtpRejectsLockedAccount() {
    User locked = user("carol", true, "JBSWY3DPEHPK3PXP");
    locked.setLockedUntil(Instant.now().plusSeconds(300));
    when(userRepository.findByUsername("carol")).thenReturn(Optional.of(locked));
    var result = service.verifyVpnOtp("carol", "123456", "1.2.3.4", "abc");
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("account_locked");
  }

  @Test
  void vpnVerifyRunsPostAuthHookOnSuccess() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString())).thenReturn(Map.of());
    var result = service.verifyVpnLogin("alice", "supersecret1", null, "1.2.3.4");
    assertThat(result.allowed()).isTrue();
    verify(postAuthHookService).run("alice", "1.2.3.4");
  }

  @Test
  void vpnVerifyDoesNotRunHookOnFailure() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    service.verifyVpnLogin("alice", "wrongpass1", null, "1.2.3.4");
    verify(postAuthHookService, never()).run(anyString(), anyString());
  }

  @Test
  void vpnVerifyOtpRunsPostAuthHookOnSuccess() {
    String secret = new TotpService().generateSecret();
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", true, secret)));
    var phaseOne = service.verifyVpnLogin("alice", "supersecret1", null, "1.2.3.4");
    var result = service.verifyVpnOtp("alice", totpCode(secret), "1.2.3.4", phaseOne.pendingId());
    assertThat(result.allowed()).isTrue();
    verify(postAuthHookService).run("alice", "1.2.3.4");
  }

  @Test
  void vpnVerifyLoginDeniedWhenIpBlocked() {
    when(ipFailureTracker.isBlocked("9.9.9.9")).thenReturn(true);
    var result = service.verifyVpnLogin("alice", "supersecret1", null, "9.9.9.9");
    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo("ip_blocked");
    verify(userRepository, never()).findByUsername(anyString());
  }

  @Test
  void vpnVerifyResetsIpFailuresOnSuccess() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    when(settingsService.effectiveForUser(anyString())).thenReturn(Map.of());
    var result = service.verifyVpnLogin("alice", "supersecret1", null, "1.2.3.4");
    assertThat(result.allowed()).isTrue();
    verify(ipFailureTracker).reset("1.2.3.4");
  }

  private String totpCode(String secret) {
    try {
      return new dev.samstevens.totp.code.DefaultCodeGenerator(
              dev.samstevens.totp.code.HashingAlgorithm.SHA1, 6)
          .generate(secret, new dev.samstevens.totp.time.SystemTimeProvider().getTime() / 30);
    } catch (dev.samstevens.totp.exceptions.CodeGenerationException e) {
      throw new RuntimeException(e);
    }
  }
}
