package com.opnl.vpn.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.opnl.vpn.access.AccessRuleService;
import com.opnl.vpn.access.RuleEngine.IptablesResult;
import com.opnl.vpn.auth.AuthService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.monitor.ConnectionLogService;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.setup.SetupService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
  private final AccessRuleService ruleService;
  private final ConnectionRegistry connectionRegistry;
  private final SettingsService settingsService;
  private final ConnectionLogService connectionLogService;

  public InternalController(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      SetupService setupService,
      AuthService authService,
      AccessRuleService ruleService,
      ConnectionRegistry connectionRegistry,
      SettingsService settingsService,
      ConnectionLogService connectionLogService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.setupService = setupService;
    this.authService = authService;
    this.ruleService = ruleService;
    this.connectionRegistry = connectionRegistry;
    this.settingsService = settingsService;
    this.connectionLogService = connectionLogService;
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
   * Credential verification for auth-user-pass-verify (phase 1). Supports password-only and
   * password+TOTP (static-challenge MFA). When the account requires MFA and no code was supplied,
   * the verify-user-pass.sh script triggers the auth-pending flow and completes it via {@link
   * #verifyOtp}.
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

  /**
   * Second factor for the OpenVPN auth-pending flow (client-crresponse). The password was already
   * accepted in phase 1 ({@link #verify}); only the TOTP code is checked here.
   */
  @PostMapping("/auth/verify-otp")
  public VerifyResult verifyOtp(@RequestBody VerifyOtpRequest request) {
    if (!setupService.complete()) {
      return new VerifyResult(false, "setup_incomplete");
    }
    AuthService.VpnVerification result =
        authService.verifyVpnOtp(request.username(), request.otp(), request.remoteIp());
    return new VerifyResult(result.allowed(), result.reason());
  }

  /**
   * Called by client-connect.sh when a client establishes a tunnel. Authorizes the user and returns
   * config pushes plus the per-client iptables commands to install (empty when no rules apply).
   */
  @PostMapping("/connect")
  public ConnectResult connect(@RequestBody ConnectRequest request) {
    User user =
        request.commonName() == null || request.commonName().isBlank()
            ? userRepository.findByUsername(request.username()).orElse(null)
            : userRepository.findByUsername(request.commonName()).orElse(null);
    if (user == null) {
      return ConnectResult.deny("unknown_user");
    }
    if (user.isBanned() || user.isLocked(Instant.now())) {
      return ConnectResult.deny(user.isBanned() ? "user_banned" : "user_locked");
    }
    int maxConnections = maxConnections(user);
    if (maxConnections > 0
        && connectionRegistry.countByUsername(user.getUsername()) >= maxConnections) {
      return ConnectResult.deny("max_connections");
    }
    connectionRegistry.register(
        user.getUsername(),
        user.getUsername(),
        request.virtualIp(),
        request.remoteIp(),
        request.daemonName());
    connectionLogService.sessionStarted(
        user.getUsername(),
        user.getUsername(),
        request.virtualIp(),
        request.remoteIp(),
        request.daemonName());
    IptablesResult result =
        ruleService.iptablesFor(user.getUsername(), request.virtualIp(), user.getId());
    return new ConnectResult(true, null, List.of(), result.apply(), result.remove());
  }

  /**
   * Called by client-disconnect.sh. Returns the iptables teardown commands for the client's chain.
   */
  @PostMapping("/disconnect")
  public DisconnectResult disconnect(@RequestBody ConnectRequest request) {
    User user = userRepository.findByUsername(request.commonName()).orElse(null);
    if (user == null) {
      return new DisconnectResult(List.of());
    }
    connectionRegistry.unregister(user.getUsername());
    connectionLogService.sessionEnded(user.getUsername());
    IptablesResult result =
        ruleService.iptablesFor(user.getUsername(), request.virtualIp(), user.getId());
    return new DisconnectResult(result.remove());
  }

  /**
   * Records learn-address events (add/update/delete) so active virtual IPs can be correlated with
   * users. Fire-and-forget by design; failures are non-fatal.
   */
  @PostMapping("/learn-address")
  public void learnAddress(@RequestBody LearnAddressRequest request) {
    connectionRegistry.learn(request.operation(), request.address(), request.commonName());
  }

  public record ConnectRequest(
      String commonName, String username, String daemonName, String remoteIp, String virtualIp) {}

  public record LearnAddressRequest(String operation, String address, String commonName) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ConnectResult(
      boolean allowed,
      String reason,
      List<String> pushes,
      List<String> iptablesApply,
      List<String> iptablesRemove) {

    static ConnectResult deny(String reason) {
      return new ConnectResult(false, reason, List.of(), List.of(), List.of());
    }
  }

  public record DisconnectResult(List<String> remove) {}

  public record SeedRequest(String username, String password) {}

  public record SeedResult(boolean created, String username) {}

  public record VerifyRequest(
      String username, String password, String otp, String commonName, String remoteIp) {}

  public record VerifyOtpRequest(String username, String otp, String remoteIp) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record VerifyResult(boolean allowed, String reason) {}

  private int maxConnections(User user) {
    Map<String, Object> effective = settingsService.effectiveForUser(user.getId());
    return asInt(effective.get(SettingKeys.MAX_CONNECTIONS));
  }

  private static int asInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String s) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }
}
