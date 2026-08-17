package com.passagevpn.api.admin;

import com.passagevpn.common.ApiException;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin user management API. ADMIN and GROUP_ADMIN can manage users; only ADMIN manages roles. */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAnyRole('ADMIN', 'GROUP_ADMIN')")
@Tag(name = "Admin - Users", description = "User lifecycle and settings (ADMIN/GROUP_ADMIN)")
public class UserAdminController {

  private final UserAdminService userAdminService;
  private final UserRepository userRepository;

  public UserAdminController(UserAdminService userAdminService, UserRepository userRepository) {
    this.userAdminService = userAdminService;
    this.userRepository = userRepository;
  }

  @GetMapping
  public List<UserDto> list(
      Authentication authentication, @RequestParam(required = false) String search) {
    return userAdminService.listUsers(actor(authentication), search);
  }

  @PostMapping("/bulk")
  public int bulk(Authentication authentication, @Valid @RequestBody BulkRequest request) {
    return userAdminService.bulk(
        actor(authentication),
        request.action(),
        request.ids(),
        request.options() == null ? UserAdminService.DeleteOptions.none() : request.options());
  }

  @GetMapping("/{id}")
  public UserDto get(Authentication authentication, @PathVariable String id) {
    return userAdminService.getUser(actor(authentication), id);
  }

  @PostMapping
  public UserDto create(
      Authentication authentication, @Valid @RequestBody UserCreateRequest request) {
    return userAdminService.createUser(actor(authentication), request);
  }

  @PutMapping("/{id}")
  public UserDto update(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody UserUpdateRequest request) {
    return userAdminService.updateUser(actor(authentication), id, request);
  }

  @DeleteMapping("/{id}")
  public void delete(
      Authentication authentication,
      @PathVariable String id,
      @RequestBody(required = false) UserAdminService.DeleteOptions options) {
    userAdminService.deleteUser(
        actor(authentication),
        id,
        options == null ? UserAdminService.DeleteOptions.none() : options);
  }

  @PostMapping("/{id}/reset-password")
  public void resetPassword(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody PasswordRequest request) {
    userAdminService.resetPassword(actor(authentication), id, request.password());
  }

  @PostMapping("/{id}/ban")
  public UserDto ban(Authentication authentication, @PathVariable String id) {
    return userAdminService.setBanned(actor(authentication), id, true);
  }

  @PostMapping("/{id}/unban")
  public UserDto unban(Authentication authentication, @PathVariable String id) {
    return userAdminService.setBanned(actor(authentication), id, false);
  }

  @PostMapping("/{id}/mfa/setup")
  @PreAuthorize("hasRole('ADMIN')")
  public UserAdminService.MfaSetup mfaSetup(
      Authentication authentication, @PathVariable String id) {
    return userAdminService.setupMfa(id);
  }

  @PostMapping("/{id}/mfa/enable")
  @PreAuthorize("hasRole('ADMIN')")
  public UserDto mfaEnable(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody MfaEnableRequest request) {
    return userAdminService.enableMfa(actor(authentication), id, request.code());
  }

  @PostMapping("/{id}/mfa/disable")
  @PreAuthorize("hasRole('ADMIN')")
  public UserDto mfaDisable(Authentication authentication, @PathVariable String id) {
    return userAdminService.disableMfa(actor(authentication), id);
  }

  @PutMapping("/{id}/static-ip")
  public UserDto setStaticIp(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody StaticIpRequest request) {
    return userAdminService.setStaticIp(actor(authentication), id, request.staticIp());
  }

  @PostMapping("/{id}/static-ip/allocate")
  public UserDto allocateStaticIp(Authentication authentication, @PathVariable String id) {
    return userAdminService.allocateStaticIp(actor(authentication), id);
  }

  @DeleteMapping("/{id}/static-ip")
  public UserDto clearStaticIp(Authentication authentication, @PathVariable String id) {
    return userAdminService.clearStaticIp(actor(authentication), id);
  }

  @PutMapping("/{id}/static-ipv6")
  public UserDto setStaticIpv6(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody StaticIpv6Request request) {
    return userAdminService.setStaticIpv6(actor(authentication), id, request.staticIpv6());
  }

  @PostMapping("/{id}/static-ipv6/allocate")
  public UserDto allocateStaticIpv6(Authentication authentication, @PathVariable String id) {
    return userAdminService.allocateStaticIpv6(actor(authentication), id);
  }

  @DeleteMapping("/{id}/static-ipv6")
  public UserDto clearStaticIpv6(Authentication authentication, @PathVariable String id) {
    return userAdminService.clearStaticIpv6(actor(authentication), id);
  }

  @GetMapping("/{id}/settings")
  public Map<String, Object> settings(Authentication authentication, @PathVariable String id) {
    return userAdminService.userSettings(actor(authentication), id);
  }

  @GetMapping("/{id}/settings/effective")
  public Map<String, Object> effectiveSettings(
      Authentication authentication, @PathVariable String id) {
    return userAdminService.effectiveSettings(actor(authentication), id);
  }

  @PutMapping("/{id}/settings/{key}")
  public Map<String, Object> setSetting(
      Authentication authentication,
      @PathVariable String id,
      @PathVariable String key,
      @RequestBody Object value) {
    return userAdminService.setUserSetting(actor(authentication), id, key, value);
  }

  @DeleteMapping("/{id}/settings/{key}")
  public Map<String, Object> deleteSetting(
      Authentication authentication, @PathVariable String id, @PathVariable String key) {
    return userAdminService.deleteUserSetting(actor(authentication), id, key);
  }

  private User actor(Authentication authentication) {
    return userRepository
        .findById(authentication.getPrincipal().toString())
        .orElseThrow(() -> ApiException.unauthorized("unauthorized", "Authentication required"));
  }

  public record PasswordRequest(@NotBlank @Size(min = 8, max = 128) String password) {}

  public record MfaEnableRequest(@NotBlank String code) {}

  public record BulkRequest(
      @NotNull UserAdminService.BulkAction action,
      @NotEmpty List<@NotBlank String> ids,
      UserAdminService.DeleteOptions options) {}

  public record StaticIpRequest(String staticIp) {}

  public record StaticIpv6Request(String staticIpv6) {}
}
