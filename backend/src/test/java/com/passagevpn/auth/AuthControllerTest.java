package com.passagevpn.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.api.admin.UserDto;
import com.passagevpn.api.portal.PortalAccountService;
import com.passagevpn.common.ApiException;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.setup.SetupService;
import com.passagevpn.user.User;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/** Web-layer tests for the auth API (direct controller invocation, mocked AuthService). */
class AuthControllerTest {

  private AuthService authService;
  private SetupService setupService;
  private SettingsService settingsService;
  private AuthController controller;

  @BeforeEach
  void setUp() {
    authService = mock(AuthService.class);
    setupService = mock(SetupService.class);
    settingsService = mock(SettingsService.class);
    when(setupService.complete()).thenReturn(true);
    controller = new AuthController(authService, setupService, settingsService);
  }

  private AuthService.TokenResponse tokens() {
    return new AuthService.TokenResponse("access", "refresh");
  }

  @Test
  void loginDelegatesToAuthService() {
    when(authService.login("alice", "supersecret1"))
        .thenReturn(new AuthService.MfaChallengeResponse(false, false, null, "access", "refresh"));

    AuthService.MfaChallengeResponse response =
        controller.login(new AuthController.LoginRequest("alice", "supersecret1"));

    assertThat(response.mfaRequired()).isFalse();
    assertThat(response.accessToken()).isEqualTo("access");
    assertThat(response.refreshToken()).isEqualTo("refresh");
    verify(authService).login("alice", "supersecret1");
  }

  @Test
  void loginReturnsMfaChallengeWhenRequired() {
    when(authService.login("alice", "supersecret1"))
        .thenReturn(new AuthService.MfaChallengeResponse(true, false, "pre", null, null));

    AuthService.MfaChallengeResponse response =
        controller.login(new AuthController.LoginRequest("alice", "supersecret1"));

    assertThat(response.mfaRequired()).isTrue();
    assertThat(response.preAuthToken()).isEqualTo("pre");
    assertThat(response.accessToken()).isNull();
  }

  @Test
  void loginReturnsForcedEnrollmentWhenPolicyRequiresMfa() {
    when(authService.login("alice", "supersecret1"))
        .thenReturn(new AuthService.MfaChallengeResponse(false, true, "pre", null, null));

    AuthService.MfaChallengeResponse response =
        controller.login(new AuthController.LoginRequest("alice", "supersecret1"));

    assertThat(response.mustEnrollMfa()).isTrue();
    assertThat(response.preAuthToken()).isEqualTo("pre");
  }

  @Test
  void loginRejectsWhenSetupIncomplete() {
    when(setupService.complete()).thenReturn(false);

    assertThatThrownBy(
            () -> controller.login(new AuthController.LoginRequest("alice", "supersecret1")))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(((ApiException) e).getCode()).isEqualTo("setup_incomplete");
            });
    verify(authService, never()).login(anyString(), anyString());
  }

  @Test
  void loginPropagatesInvalidCredentials() {
    when(authService.login(anyString(), anyString()))
        .thenThrow(
            ApiException.unauthorized("invalid_credentials", "Invalid username or password"));

    assertThatThrownBy(
            () -> controller.login(new AuthController.LoginRequest("alice", "wrongpass1")))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(((ApiException) e).getCode()).isEqualTo("invalid_credentials");
            });
  }

  @Test
  void mfaDelegatesToAuthService() {
    when(authService.mfa("pre", "123456")).thenReturn(tokens());

    AuthService.TokenResponse response =
        controller.mfa(new AuthController.MfaRequest("pre", "123456"));

    assertThat(response.accessToken()).isEqualTo("access");
    verify(authService).mfa("pre", "123456");
  }

  @Test
  void mfaPropagatesInvalidChallenge() {
    when(authService.mfa(anyString(), anyString()))
        .thenThrow(ApiException.unauthorized("mfa_challenge_invalid", "MFA challenge expired"));

    assertThatThrownBy(() -> controller.mfa(new AuthController.MfaRequest("stale", "123456")))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(((ApiException) e).getCode()).isEqualTo("mfa_challenge_invalid");
            });
  }

  @Test
  void mfaEnrollDelegatesToAuthService() {
    when(authService.enrollStart("pre"))
        .thenReturn(
            new PortalAccountService.MfaSetup(
                "secret", "otpauth://totp/", "data:image/png;base64,"));

    PortalAccountService.MfaSetup setup =
        controller.mfaEnroll(new AuthController.MfaEnrollRequest("pre"));

    assertThat(setup.secret()).isEqualTo("secret");
    assertThat(setup.qrDataUrl()).startsWith("data:image/png;base64,");
    verify(authService).enrollStart("pre");
  }

  @Test
  void mfaEnrollConfirmDelegatesToAuthService() {
    when(authService.enrollConfirm("pre", "123456")).thenReturn(tokens());

    AuthService.TokenResponse response =
        controller.mfaEnrollConfirm(new AuthController.MfaRequest("pre", "123456"));

    assertThat(response.accessToken()).isEqualTo("access");
    verify(authService).enrollConfirm("pre", "123456");
  }

  @Test
  void refreshDelegatesToAuthService() {
    when(authService.refresh("refresh")).thenReturn(tokens());

    AuthService.TokenResponse response =
        controller.refresh(new AuthController.RefreshRequest("refresh"));

    assertThat(response.accessToken()).isEqualTo("access");
    assertThat(response.refreshToken()).isEqualTo("refresh");
    verify(authService).refresh("refresh");
  }

  @Test
  void refreshPropagatesInvalidToken() {
    when(authService.refresh(anyString()))
        .thenThrow(ApiException.unauthorized("invalid_token", "Refresh token invalid or expired"));

    assertThatThrownBy(() -> controller.refresh(new AuthController.RefreshRequest("stale")))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(((ApiException) e).getCode()).isEqualTo("invalid_token");
            });
  }

  @Test
  void logoutRevokesProvidedToken() {
    controller.logout(new AuthController.LogoutRequest("refresh"));

    verify(authService).logout("refresh");
  }

  @Test
  void logoutWithoutBodyDelegatesNull() {
    controller.logout(null);

    verify(authService).logout(null);
  }

  @Test
  void meReturnsCurrentUserWithEffectiveFlags() {
    User user = meUser();
    Authentication authentication = authentication("u1");
    when(authService.me("u1")).thenReturn(user);
    when(settingsService.userSettings("u1"))
        .thenReturn(Map.of(SettingKeys.MUST_CHANGE_PASSWORD, true));
    when(settingsService.effectiveForUser("u1")).thenReturn(Map.of(SettingKeys.REQUIRE_MFA, true));

    UserDto dto = controller.me(authentication);

    assertThat(dto.username()).isEqualTo("alice");
    assertThat(dto.mfaRequired()).isTrue();
    assertThat(dto.mustChangePassword()).isTrue();
    verify(authService).me("u1");
  }

  @Test
  void meClearsFlagsWhenNotConfigured() {
    User user = meUser();
    Authentication authentication = authentication("u1");
    when(authService.me("u1")).thenReturn(user);
    when(settingsService.userSettings("u1")).thenReturn(Map.of());
    when(settingsService.effectiveForUser("u1")).thenReturn(Map.of());

    UserDto dto = controller.me(authentication);

    assertThat(dto.mfaRequired()).isFalse();
    assertThat(dto.mustChangePassword()).isFalse();
  }

  @Test
  void meRejectsMissingAuthentication() {
    assertThatThrownBy(() -> controller.me(null))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(((ApiException) e).getCode()).isEqualTo("unauthorized");
            });
    verify(authService, never()).me(anyString());
  }

  @Test
  void mePropagatesUnknownUser() {
    Authentication authentication = authentication("u1");
    when(authService.me("u1"))
        .thenThrow(ApiException.unauthorized("invalid_token", "Account not found"));

    assertThatThrownBy(() -> controller.me(authentication))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(((ApiException) e).getCode()).isEqualTo("invalid_token");
            });
  }

  private User meUser() {
    return User.builder()
        .id("u1")
        .username("alice")
        .fullName("Alice Wonder")
        .email("alice@example.com")
        .role(User.Role.USER)
        .createdAt(Instant.now())
        .build();
  }

  private Authentication authentication(String principal) {
    Authentication authentication = mock(Authentication.class);
    when(authentication.getPrincipal()).thenReturn(principal);
    return authentication;
  }
}
