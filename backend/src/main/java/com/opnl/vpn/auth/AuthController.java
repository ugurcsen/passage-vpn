package com.opnl.vpn.auth;

import com.opnl.vpn.api.admin.UserDto;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.setup.SetupService;
import com.opnl.vpn.user.User;
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
  public AuthService.MfaChallengeResponse login(@Valid @RequestBody LoginRequest request) {
    if (!setupService.complete()) {
      throw ApiException.conflict("setup_incomplete", "Setup must be completed before login");
    }
    return authService.login(request.username(), request.password());
  }

  @PostMapping("/mfa")
  public AuthService.TokenResponse mfa(@Valid @RequestBody MfaRequest request) {
    return authService.mfa(request.preAuthToken(), request.code());
  }

  @PostMapping("/refresh")
  public AuthService.TokenResponse refresh(@RequestBody RefreshRequest request) {
    return authService.refresh(request.refreshToken());
  }

  @PostMapping("/logout")
  public void logout(@RequestBody(required = false) LogoutRequest request) {
    authService.logout(request == null ? null : request.refreshToken());
  }

  @GetMapping("/me")
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
