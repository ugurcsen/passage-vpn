package com.passagevpn.api.portal;

import com.passagevpn.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service certificate endpoints for the client portal: view and rotate the user's own VPN
 * certificate. Any authenticated user can act on their own certificate only.
 */
@RestController
@RequestMapping("/api/portal/cert")
@Tag(name = "Portal - Certificate", description = "Self-service VPN certificate management")
public class PortalCertController {

  private final PortalAccountService accountService;

  public PortalCertController(PortalAccountService accountService) {
    this.accountService = accountService;
  }

  @GetMapping
  @Operation(summary = "Show the current user's VPN certificate")
  public PortalAccountService.CertificateInfo myCert(Authentication authentication) {
    return accountService.myCertificate(principal(authentication));
  }

  @PostMapping("/rotate")
  @Operation(summary = "Revoke and reissue the current user's VPN certificate")
  public PortalAccountService.CertificateInfo rotate(Authentication authentication) {
    return accountService.rotateCertificate(principal(authentication));
  }

  private String principal(Authentication authentication) {
    if (authentication == null || authentication.getName() == null) {
      throw ApiException.unauthorized("unauthorized", "Authentication required");
    }
    return authentication.getName();
  }
}
