package com.opnl.vpn.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.security.JwtService;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.RefreshToken;
import com.opnl.vpn.user.RefreshTokenRepository;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
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
  private OpnlProperties properties;
  private AuthService service;
  private JwtService jwtService;
  private BCryptPasswordEncoder encoder;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    refreshTokenRepository = mock(RefreshTokenRepository.class);
    settingsService = mock(SettingsService.class);
    properties = mock(OpnlProperties.class);
    OpnlProperties.Jwt jwt = mock(OpnlProperties.Jwt.class);
    when(jwt.secret()).thenReturn(SECRET);
    when(jwt.accessTtl()).thenReturn(Duration.ofMinutes(15));
    when(jwt.refreshTtl()).thenReturn(Duration.ofDays(14));
    when(properties.jwt()).thenReturn(jwt);
    OpnlProperties.Auth auth = mock(OpnlProperties.Auth.class);
    when(auth.lockoutMaxAttempts()).thenReturn(3);
    when(auth.lockoutWindowSeconds()).thenReturn(300);
    when(auth.lockoutDurationSeconds()).thenReturn(300);
    when(properties.auth()).thenReturn(auth);
    jwtService = new JwtService(properties);
    encoder = new BCryptPasswordEncoder();
    service =
        new AuthService(
            userRepository,
            refreshTokenRepository,
            encoder,
            jwtService,
            new TotpService(),
            settingsService,
            properties);
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
    assertThat(result.preAuthToken()).isNotBlank();
    assertThat(result.accessToken()).isNull();
  }

  @Test
  void loginRejectsWrongPassword() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    assertThatThrownBy(() -> service.login("alice", "wrongpass1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_credentials"));
    verify(userRepository).save(any());
  }

  @Test
  void loginLocksAccountAfterMaxAttempts() {
    when(userRepository.findByUsername("alice"))
        .thenReturn(Optional.of(user("alice", false, null)));
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> service.login("alice", "wrongpass1"))
          .isInstanceOf(ApiException.class);
    }
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
}
