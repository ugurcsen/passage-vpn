package com.opnl.vpn.api.portal;

import com.opnl.vpn.api.admin.UserDto;
import com.opnl.vpn.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service account endpoints for the client portal: TOTP MFA provisioning and password
 * changes. Any authenticated user can act on their own account only.
 */
@RestController
@RequestMapping("/api/portal/account")
public class PortalAccountController {

  private final PortalAccountService accountService;

  public PortalAccountController(PortalAccountService accountService) {
    this.accountService = accountService;
  }

  public record MfaSetupRequest(@NotBlank String currentPassword) {}

  public record MfaEnableRequest(@NotBlank String code) {}

  public record PasswordRequest(
      @NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 128) String newPassword) {}

  @PostMapping("/mfa/setup")
  public PortalAccountService.MfaSetup mfaSetup(
      @Valid @RequestBody MfaSetupRequest request, Authentication authentication) {
    return accountService.setupMfa(principal(authentication), request.currentPassword());
  }

  @PostMapping("/mfa/enable")
  public UserDto mfaEnable(
      @Valid @RequestBody MfaEnableRequest request, Authentication authentication) {
    return accountService.enableMfa(principal(authentication), request.code());
  }

  @PostMapping("/mfa/disable")
  public UserDto mfaDisable(
      @Valid @RequestBody MfaSetupRequest request, Authentication authentication) {
    return accountService.disableMfa(principal(authentication), request.currentPassword());
  }

  @PostMapping("/password")
  public void changePassword(
      @Valid @RequestBody PasswordRequest request, Authentication authentication) {
    accountService.changePassword(
        principal(authentication), request.currentPassword(), request.newPassword());
  }

  private String principal(Authentication authentication) {
    if (authentication == null || authentication.getName() == null) {
      throw ApiException.unauthorized("unauthorized", "Authentication required");
    }
    return authentication.getName();
  }
}
