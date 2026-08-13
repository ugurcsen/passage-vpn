package com.opnl.vpn.auth;

import com.opnl.vpn.api.admin.UserDto;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.setup.SetupService;
import com.opnl.vpn.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Web session endpoints: login, MFA, refresh, logout, current user. */
@RestController
@RequestMapping("/api/auth")
@Tag(
    name = "Authentication",
    description =
        "Login, MFA, token refresh and logout. To test authenticated endpoints in Swagger UI: "
            + "call /login (or /mfa when TOTP is enabled), copy the returned accessToken and paste "
            + "it as 'Bearer <token>' in the Authorize dialog.")
public class AuthController {

  private final AuthService authService;
  private final SetupService setupService;
  private final SettingsService settingsService;

  public AuthController(
      AuthService authService, SetupService setupService, SettingsService settingsService) {
    this.authService = authService;
    this.setupService = setupService;
    this.settingsService = settingsService;
  }

  public record LoginRequest(@NotBlank String username, @NotBlank @Size(min = 8) String password) {}

  public record MfaRequest(@NotBlank String preAuthToken, @NotBlank String code) {}

  public record RefreshRequest(String refreshToken) {}

  public record LogoutRequest(String refreshToken) {}

  @PostMapping("/login")
  @Operation(
      summary = "First login factor",
      description =
          "Validates username/password. When the account has TOTP enabled the response contains "
              + "mfaRequired=true and a preAuthToken that must be redeemed at /mfa. Otherwise "
              + "accessToken/refreshToken are returned directly.")
  public AuthService.MfaChallengeResponse login(@Valid @RequestBody LoginRequest request) {
    if (!setupService.complete()) {
      throw ApiException.conflict("setup_incomplete", "Setup must be completed before login");
    }
    return authService.login(request.username(), request.password());
  }

  @PostMapping("/mfa")
  @Operation(
      summary = "Second login factor (TOTP)",
      description =
          "Redeems a preAuthToken from /login together with a TOTP code. Returns the final "
              + "accessToken/refreshToken pair.")
  public AuthService.TokenResponse mfa(@Valid @RequestBody MfaRequest request) {
    return authService.mfa(request.preAuthToken(), request.code());
  }

  @PostMapping("/refresh")
  @Operation(
      summary = "Rotate access token",
      description =
          "Exchanges the current refreshToken for a fresh accessToken/refreshToken pair. A "
              + "reused or revoked refresh token invalidates the whole session family.")
  public AuthService.TokenResponse refresh(@RequestBody RefreshRequest request) {
    return authService.refresh(request.refreshToken());
  }

  @PostMapping("/logout")
  @Operation(
      summary = "Revoke session",
      description =
          "Revokes the given refreshToken, ending the session. The access token stays valid until "
              + "it expires on its own.")
  public void logout(@RequestBody(required = false) LogoutRequest request) {
    authService.logout(request == null ? null : request.refreshToken());
  }

  @GetMapping("/me")
  @Operation(
      summary = "Current user",
      description =
          "Returns the profile and roles of the authenticated user identified by the bearer "
              + "access token.")
  public UserDto me(Authentication authentication) {
    if (authentication == null || authentication.getPrincipal() == null) {
      throw ApiException.unauthorized("unauthorized", "Authentication required");
    }
    User user = authService.me(authentication.getPrincipal().toString());
    boolean mustChangePassword =
        Boolean.TRUE.equals(
            settingsService.userSettings(user.getId()).get(SettingKeys.MUST_CHANGE_PASSWORD));
    return UserDto.from(user, mustChangePassword);
  }
}
