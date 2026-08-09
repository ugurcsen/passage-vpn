package com.opnl.vpn.api.admin;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
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

/** Admin user management API. ADMIN and RESELLER can manage users; only ADMIN manages roles. */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAnyRole('ADMIN', 'RESELLER')")
public class UserAdminController {

  private final UserAdminService userAdminService;
  private final UserRepository userRepository;

  public UserAdminController(UserAdminService userAdminService, UserRepository userRepository) {
    this.userAdminService = userAdminService;
    this.userRepository = userRepository;
  }

  @GetMapping
  public List<UserDto> list(@RequestParam(required = false) String search) {
    return userAdminService.listUsers(search);
  }

  @PostMapping("/bulk")
  public int bulk(Authentication authentication, @Valid @RequestBody BulkRequest request) {
    return userAdminService.bulk(actor(authentication), request.action(), request.ids());
  }

  @GetMapping("/{id}")
  public UserDto get(@PathVariable String id) {
    return userAdminService.getUser(id);
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
  public void delete(Authentication authentication, @PathVariable String id) {
    userAdminService.deleteUser(actor(authentication), id);
  }

  @PostMapping("/{id}/reset-password")
  public void resetPassword(@PathVariable String id, @Valid @RequestBody PasswordRequest request) {
    userAdminService.resetPassword(id, request.password());
  }

  @PostMapping("/{id}/ban")
  public UserDto ban(@PathVariable String id) {
    return userAdminService.setBanned(id, true);
  }

  @PostMapping("/{id}/unban")
  public UserDto unban(@PathVariable String id) {
    return userAdminService.setBanned(id, false);
  }

  @PostMapping("/{id}/mfa/setup")
  @PreAuthorize("hasRole('ADMIN')")
  public UserAdminService.MfaSetup mfaSetup(@PathVariable String id) {
    return userAdminService.setupMfa(id);
  }

  @PostMapping("/{id}/mfa/enable")
  @PreAuthorize("hasRole('ADMIN')")
  public UserDto mfaEnable(@PathVariable String id, @Valid @RequestBody MfaEnableRequest request) {
    return userAdminService.enableMfa(id, request.code());
  }

  @PostMapping("/{id}/mfa/disable")
  @PreAuthorize("hasRole('ADMIN')")
  public UserDto mfaDisable(@PathVariable String id) {
    return userAdminService.disableMfa(id);
  }

  @GetMapping("/{id}/settings")
  public Map<String, Object> settings(@PathVariable String id) {
    return userAdminService.userSettings(id);
  }

  @GetMapping("/{id}/settings/effective")
  public Map<String, Object> effectiveSettings(@PathVariable String id) {
    return userAdminService.effectiveSettings(id);
  }

  @PutMapping("/{id}/settings/{key}")
  public Map<String, Object> setSetting(
      @PathVariable String id, @PathVariable String key, @RequestBody Object value) {
    return userAdminService.setUserSetting(id, key, value);
  }

  @DeleteMapping("/{id}/settings/{key}")
  public Map<String, Object> deleteSetting(@PathVariable String id, @PathVariable String key) {
    return userAdminService.deleteUserSetting(id, key);
  }

  private User actor(Authentication authentication) {
    return userRepository
        .findById(authentication.getPrincipal().toString())
        .orElseThrow(() -> ApiException.unauthorized("unauthorized", "Authentication required"));
  }

  public record PasswordRequest(@NotBlank @Size(min = 8, max = 128) String password) {}

  public record MfaEnableRequest(@NotBlank String code) {}

  public record BulkRequest(
      @NotNull UserAdminService.BulkAction action, @NotEmpty List<@NotBlank String> ids) {}
}
