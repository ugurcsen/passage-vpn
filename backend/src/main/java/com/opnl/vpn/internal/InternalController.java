package com.opnl.vpn.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.opnl.vpn.auth.AuthService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.setup.SetupService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoints called by OpenVPN helper scripts inside the restricted docker network. Not
 * part of the public admin/portal API surface.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
public class InternalController {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final SetupService setupService;
  private final AuthService authService;

  public InternalController(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      SetupService setupService,
      AuthService authService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.setupService = setupService;
    this.authService = authService;
  }

  /**
   * Creates the initial admin user without the wizard (used by `make seed-admin`). Returns 409 when
   * an admin already exists.
   */
  @PostMapping("/seed-admin")
  public SeedResult seedAdmin(@RequestBody SeedRequest request) {
    if (userRepository.countByRole(User.Role.ADMIN) > 0) {
      throw ApiException.conflict("admin_exists", "An admin account already exists");
    }
    if (request.username() == null
        || request.password() == null
        || request.password().length() < 8) {
      throw ApiException.badRequest("weak_password", "Username required; password min 8 chars");
    }
    userRepository.save(
        User.builder()
            .id(UUID.randomUUID().toString())
            .username(request.username())
            .passwordHash(passwordEncoder.encode(request.password()))
            .role(User.Role.ADMIN)
            .createdAt(Instant.now())
            .build());
    log.info("Seeded admin user '{}'", request.username());
    return new SeedResult(true, request.username());
  }

  /**
   * Credential verification for auth-user-pass-verify. Supports password-only and password+TOTP
   * (static-challenge MFA). The OpenVPN scripts pass the code in the challenge response.
   */
  @PostMapping("/auth/verify")
  public VerifyResult verify(@RequestBody VerifyRequest request) {
    if (!setupService.complete()) {
      return new VerifyResult(false, "setup_incomplete");
    }
    AuthService.VpnVerification result =
        authService.verifyVpnLogin(
            request.username(), request.password(), request.otp(), request.remoteIp());
    return new VerifyResult(result.allowed(), result.reason());
  }

  public record SeedRequest(String username, String password) {}

  public record SeedResult(boolean created, String username) {}

  public record VerifyRequest(
      String username, String password, String otp, String commonName, String remoteIp) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record VerifyResult(boolean allowed, String reason) {}
}
