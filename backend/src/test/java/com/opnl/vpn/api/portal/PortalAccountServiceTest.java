package com.opnl.vpn.api.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.api.admin.UserDto;
import com.opnl.vpn.auth.TotpService;
import com.opnl.vpn.auth.spi.AuthProviderManager;
import com.opnl.vpn.auth.spi.LocalAuthProvider;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.RefreshToken;
import com.opnl.vpn.user.RefreshTokenRepository;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PortalAccountServiceTest {

  private static final String PASSWORD = "supersecret1";

  private UserRepository userRepository;
  private RefreshTokenRepository refreshTokenRepository;
  private SettingsService settingsService;
  private BCryptPasswordEncoder encoder;
  private PortalAccountService service;

  private User alice() {
    return User.builder()
        .id("u1")
        .username("alice")
        .passwordHash(encoder.encode(PASSWORD))
        .role(User.Role.USER)
        .createdAt(Instant.now())
        .build();
  }

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    refreshTokenRepository = mock(RefreshTokenRepository.class);
    settingsService = mock(SettingsService.class);
    encoder = new BCryptPasswordEncoder();
    OpnlProperties properties = mock(OpnlProperties.class);
    OpnlProperties.Auth auth = mock(OpnlProperties.Auth.class);
    when(auth.provider()).thenReturn("local");
    when(properties.auth()).thenReturn(auth);
    AuthProviderManager authProviderManager =
        new AuthProviderManager(
            properties, List.of(new LocalAuthProvider(userRepository, encoder)));
    service =
        new PortalAccountService(
            userRepository,
            refreshTokenRepository,
            encoder,
            new TotpService(),
            settingsService,
            authProviderManager);
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice()));
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice()));
  }

  private static String validCode(User user) {
    // Recompute a fresh 6-digit code from the stored secret (SameWindow accepted).
    String secret = user.getMfaSecret();
    try {
      return new dev.samstevens.totp.code.DefaultCodeGenerator(
              dev.samstevens.totp.code.HashingAlgorithm.SHA1, 6)
          .generate(secret, new dev.samstevens.totp.time.SystemTimeProvider().getTime() / 30);
    } catch (dev.samstevens.totp.exceptions.CodeGenerationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void setupMfaRequiresCurrentPassword() {
    assertThatThrownBy(() -> service.setupMfa("u1", "wrong-password"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "invalid_credentials");
  }

  @Test
  void setupMfaStoresSecretAndReturnsQr() {
    PortalAccountService.MfaSetup setup = service.setupMfa("u1", PASSWORD);

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    assertThat(captor.getValue().getMfaSecret()).isNotBlank();
    assertThat(setup.secret()).isNotBlank();
    assertThat(setup.otpAuthUrl()).startsWith("otpauth://totp/");
    assertThat(setup.qrDataUrl()).startsWith("data:image/png;base64,");
  }

  @Test
  void enableMfaActivatesAfterValidCode() {
    User user = alice();
    user.setMfaSecret(new TotpService().generateSecret());
    when(userRepository.findById("u1")).thenReturn(Optional.of(user));

    UserDto dto = service.enableMfa("u1", validCode(user));

    assertThat(dto.mfaEnabled()).isTrue();
    assertThat(user.isMfaEnabled()).isTrue();
  }

  @Test
  void enableMfaRejectsInvalidCode() {
    User user = alice();
    user.setMfaSecret(new TotpService().generateSecret());
    when(userRepository.findById("u1")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.enableMfa("u1", "000000"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "invalid_code");
  }

  @Test
  void disableMfaRequiresCurrentPasswordAndClearsSecret() {
    User user = alice();
    user.setMfaEnabled(true);
    user.setMfaSecret("SECRET");
    when(userRepository.findById("u1")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.disableMfa("u1", "wrong-password"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "invalid_credentials");

    UserDto dto = service.disableMfa("u1", PASSWORD);

    assertThat(dto.mfaEnabled()).isFalse();
    assertThat(user.getMfaSecret()).isNull();
  }

  @Test
  void changePasswordRejectsWeakNewPassword() {
    assertThatThrownBy(() -> service.changePassword("u1", PASSWORD, "short"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "weak_password");
  }

  @Test
  void changePasswordRequiresCurrentPassword() {
    assertThatThrownBy(() -> service.changePassword("u1", "wrong-password", "newpassword1"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "invalid_credentials");
  }

  @Test
  void changePasswordUpdatesHashRevokesTokensAndClearsMustChangeFlag() {
    RefreshToken token = RefreshToken.builder().id("t1").userId("u1").build();
    when(refreshTokenRepository.findByUserId("u1")).thenReturn(List.of(token));

    service.changePassword("u1", PASSWORD, "newpassword1");

    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCaptor.capture());
    assertThat(encoder.matches("newpassword1", userCaptor.getValue().getPasswordHash())).isTrue();
    assertThat(token.isRevoked()).isTrue();
    verify(refreshTokenRepository).save(token);
    verify(settingsService).setUserSetting("u1", SettingKeys.MUST_CHANGE_PASSWORD, false);
  }
}
