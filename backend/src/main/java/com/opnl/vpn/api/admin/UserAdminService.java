package com.opnl.vpn.api.admin;

import com.opnl.vpn.access.AccessRuleService;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.auth.TotpService;
import com.opnl.vpn.ccd.CcdService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.pki.CertService;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin operations over users: CRUD, ban, reset password, MFA provisioning, settings. */
@Service
public class UserAdminService {

  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final GroupMemberRepository memberRepository;
  private final PasswordEncoder passwordEncoder;
  private final TotpService totpService;
  private final SettingsService settingsService;
  private final CcdService ccdService;
  private final CertService certService;
  private final AccessRuleService accessRuleService;
  private final AuditLogService auditLogService;

  public UserAdminService(
      UserRepository userRepository,
      GroupRepository groupRepository,
      GroupMemberRepository memberRepository,
      PasswordEncoder passwordEncoder,
      TotpService totpService,
      SettingsService settingsService,
      CcdService ccdService,
      CertService certService,
      AccessRuleService accessRuleService,
      AuditLogService auditLogService) {
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.memberRepository = memberRepository;
    this.passwordEncoder = passwordEncoder;
    this.totpService = totpService;
    this.settingsService = settingsService;
    this.ccdService = ccdService;
    this.certService = certService;
    this.accessRuleService = accessRuleService;
    this.auditLogService = auditLogService;
  }

  @Transactional(readOnly = true)
  public List<UserDto> listUsers() {
    return listUsers(null);
  }

  @Transactional(readOnly = true)
  public List<UserDto> listUsers(String search) {
    List<User> users = userRepository.findAll();
    Map<String, List<GroupMember>> byUser =
        memberRepository.findAll().stream()
            .collect(Collectors.groupingBy(m -> m.getId().getUserId()));
    Map<String, String> groupNames =
        groupRepository.findAll().stream().collect(Collectors.toMap(Group::getId, Group::getName));
    String needle = search == null ? "" : search.trim().toLowerCase();
    return users.stream()
        .filter(user -> matches(user, needle))
        .map(
            user -> {
              List<String> names =
                  byUser.getOrDefault(user.getId(), List.of()).stream()
                      .map(m -> groupNames.get(m.getId().getGroupId()))
                      .filter(name -> name != null)
                      .sorted()
                      .toList();
              boolean mustChange =
                  Boolean.TRUE.equals(
                      settingsService
                          .userSettings(user.getId())
                          .get(SettingKeys.MUST_CHANGE_PASSWORD));
              return UserDto.from(user, mustChange, names);
            })
        .sorted(java.util.Comparator.comparing(UserDto::username, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private boolean matches(User user, String needle) {
    if (needle.isEmpty()) {
      return true;
    }
    return user.getUsername().toLowerCase().contains(needle)
        || (user.getFullName() != null && user.getFullName().toLowerCase().contains(needle))
        || (user.getEmail() != null && user.getEmail().toLowerCase().contains(needle));
  }

  @Transactional(readOnly = true)
  public UserDto getUser(String id) {
    User user = requireUser(id);
    List<String> names =
        memberRepository.findById_UserId(id).stream()
            .map(
                m ->
                    groupRepository
                        .findById(m.getId().getGroupId())
                        .map(Group::getName)
                        .orElse(null))
            .filter(name -> name != null)
            .toList();
    boolean mustChange =
        Boolean.TRUE.equals(settingsService.userSettings(id).get(SettingKeys.MUST_CHANGE_PASSWORD));
    return UserDto.from(user, mustChange, names);
  }

  @Transactional
  public UserDto createUser(User actor, UserCreateRequest request) {
    if (userRepository.existsByUsername(request.username().trim())) {
      throw ApiException.conflict("username_taken", "Username already exists");
    }
    User.Role role = request.role() == null ? User.Role.USER : request.role();
    assertCanAssignRole(actor, role);
    User user =
        User.builder()
            .id(UUID.randomUUID().toString())
            .username(request.username().trim())
            .passwordHash(passwordEncoder.encode(request.password()))
            .fullName(request.fullName())
            .email(request.email())
            .role(role)
            .createdAt(Instant.now())
            .build();
    userRepository.save(user);
    setMemberships(user.getId(), request.groupIds());
    if (role == User.Role.RESELLER) {
      settingsService.setUserSetting(user.getId(), SettingKeys.ACCOUNT_DISABLED, false);
    }
    auditLogService.record(
        "USER_CREATE",
        AuditLogService.CAT_USER,
        user.getId(),
        "user",
        Map.of("username", user.getUsername(), "role", role.name()));
    return getUser(user.getId());
  }

  @Transactional
  public UserDto updateUser(User actor, String id, UserUpdateRequest request) {
    User user = requireUser(id);
    if (request.role() != null && request.role() != user.getRole()) {
      assertCanAssignRole(actor, request.role());
      if (user.getRole() == User.Role.ADMIN && countAdmins() <= 1) {
        throw ApiException.badRequest("last_admin", "Cannot change the role of the last admin");
      }
      user.setRole(request.role());
    }
    if (request.fullName() != null) user.setFullName(request.fullName());
    if (request.email() != null) user.setEmail(request.email());
    if (request.banned() != null) {
      if (request.banned() && user.getRole() == User.Role.ADMIN && countAdmins() <= 1) {
        throw ApiException.badRequest("last_admin", "Cannot disable the last admin");
      }
      user.setBanned(request.banned());
    }
    if (request.password() != null && !request.password().isBlank()) {
      user.setPasswordHash(passwordEncoder.encode(request.password()));
      settingsService.setUserSetting(user.getId(), SettingKeys.MUST_CHANGE_PASSWORD, true);
    }
    if (request.groupIds() != null) {
      setMemberships(user.getId(), request.groupIds());
    }
    userRepository.save(user);
    auditLogService.record(
        "USER_UPDATE",
        AuditLogService.CAT_USER,
        user.getId(),
        "user",
        Map.of("username", user.getUsername()));
    return getUser(user.getId());
  }

  @Transactional
  public void deleteUser(User actor, String id) {
    deleteUser(actor, id, DeleteOptions.none());
  }

  /**
   * Deletes a user. When {@link DeleteOptions} flags are set, related resources are cleaned up
   * first: certificates are revoked and purged from the PKI, user-targeted access rules are removed
   * (with the dnsmasq config refreshed) and the user's static IP/CCD file is cleared.
   */
  @Transactional
  public void deleteUser(User actor, String id, DeleteOptions options) {
    User user = requireUser(id);
    if (user.getId().equals(actor.getId())) {
      throw ApiException.badRequest("cannot_delete_self", "You cannot delete your own account");
    }
    if (user.getRole() == User.Role.ADMIN && countAdmins() <= 1) {
      throw ApiException.badRequest("last_admin", "Cannot delete the last admin");
    }
    if (options.deleteCertificates()) {
      certService.purgeForUser(id);
    }
    if (options.deleteAccessRules()) {
      accessRuleService.deleteForUser(id);
    }
    if (options.clearCcd()) {
      ccdService.clearStaticIp(id);
      ccdService.clearStaticIpv6(id);
    }
    memberRepository.deleteAll(memberRepository.findById_UserId(id));
    settingsService
        .userSettings(id)
        .keySet()
        .forEach(key -> settingsService.deleteUserSetting(id, key));
    userRepository.delete(user);
    Map<String, Object> detail = new java.util.HashMap<>();
    detail.put("username", user.getUsername());
    if (options.deleteCertificates()) detail.put("certificates", true);
    if (options.deleteAccessRules()) detail.put("accessRules", true);
    if (options.clearCcd()) detail.put("ccd", true);
    auditLogService.record("USER_DELETE", AuditLogService.CAT_USER, id, "user", detail);
  }

  /** Cleanup choices offered when deleting a user; all flags default to off. */
  public record DeleteOptions(
      boolean deleteCertificates, boolean deleteAccessRules, boolean clearCcd) {
    public static DeleteOptions none() {
      return new DeleteOptions(false, false, false);
    }
  }

  public enum BulkAction {
    BAN,
    UNBAN,
    DELETE
  }

  /**
   * Applies a ban/unban/delete to many users in one transaction. Guards (self-delete, last admin)
   * are enforced per user; a failing user aborts the whole batch.
   */
  @Transactional
  public int bulk(User actor, BulkAction action, List<String> ids) {
    return bulk(actor, action, ids, DeleteOptions.none());
  }

  /** Same as {@link #bulk(User, BulkAction, List)} but deletes carry the given cleanup options. */
  @Transactional
  public int bulk(User actor, BulkAction action, List<String> ids, DeleteOptions options) {
    if (ids == null || ids.isEmpty()) {
      throw ApiException.badRequest("empty_batch", "No user ids provided");
    }
    for (String id : ids) {
      switch (action) {
        case BAN -> setBanned(id, true);
        case UNBAN -> setBanned(id, false);
        case DELETE -> deleteUser(actor, id, options);
      }
    }
    auditLogService.record(
        "USER_BULK",
        AuditLogService.CAT_USER,
        null,
        "user",
        Map.of("action", action.name(), "count", ids.size()));
    return ids.size();
  }

  @Transactional
  public void resetPassword(String id, String newPassword) {
    User user = requireUser(id);
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    userRepository.save(user);
    auditLogService.record(
        "USER_PASSWORD_RESET",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("username", user.getUsername()));
  }

  @Transactional
  public UserDto setBanned(String id, boolean banned) {
    User user = requireUser(id);
    if (banned && user.getRole() == User.Role.ADMIN && countAdmins() <= 1) {
      throw ApiException.badRequest("last_admin", "Cannot disable the last admin");
    }
    user.setBanned(banned);
    userRepository.save(user);
    auditLogService.record(
        banned ? "USER_BAN" : "USER_UNBAN",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("username", user.getUsername()));
    return getUser(id);
  }

  @Transactional
  public UserDto setStaticIp(String id, String staticIp) {
    requireUser(id);
    ccdService.setStaticIp(id, staticIp);
    auditLogService.record(
        "USER_STATIC_IP_SET", AuditLogService.CAT_USER, id, "user", Map.of("staticIp", staticIp));
    return getUser(id);
  }

  /** Allocates the next free static IP from the user's group pool. */
  @Transactional
  public UserDto allocateStaticIp(String id) {
    requireUser(id);
    ccdService.allocateFromGroupPool(id);
    auditLogService.record("USER_STATIC_IP_ALLOCATE", AuditLogService.CAT_USER, id, "user", null);
    return getUser(id);
  }

  @Transactional
  public UserDto clearStaticIp(String id) {
    requireUser(id);
    ccdService.clearStaticIp(id);
    auditLogService.record("USER_STATIC_IP_CLEAR", AuditLogService.CAT_USER, id, "user", null);
    return getUser(id);
  }

  @Transactional
  public UserDto setStaticIpv6(String id, String staticIpv6) {
    requireUser(id);
    ccdService.setStaticIpv6(id, staticIpv6);
    auditLogService.record(
        "USER_STATIC_IPV6_SET",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("staticIpv6", staticIpv6));
    return getUser(id);
  }

  /** Allocates the next free static IPv6 from the user's group pool. */
  @Transactional
  public UserDto allocateStaticIpv6(String id) {
    requireUser(id);
    ccdService.allocateIpv6FromGroupPool(id);
    auditLogService.record("USER_STATIC_IPV6_ALLOCATE", AuditLogService.CAT_USER, id, "user", null);
    return getUser(id);
  }

  @Transactional
  public UserDto clearStaticIpv6(String id) {
    requireUser(id);
    ccdService.clearStaticIpv6(id);
    auditLogService.record("USER_STATIC_IPV6_CLEAR", AuditLogService.CAT_USER, id, "user", null);
    return getUser(id);
  }

  public record MfaSetup(String secret, String otpAuthUrl, String qrDataUrl) {}

  /** Generates a fresh TOTP secret; user must then confirm with {@link #enableMfa}. */
  @Transactional
  public MfaSetup setupMfa(String id) {
    User user = requireUser(id);
    String secret = totpService.generateSecret();
    user.setMfaSecret(secret);
    user.setMfaEnabled(false);
    userRepository.save(user);
    auditLogService.record(
        "USER_MFA_SETUP",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("username", user.getUsername()));
    String uri = totpService.otpAuthUri(secret, user.getUsername());
    return new MfaSetup(secret, uri, totpService.qrPngDataUrl(secret, user.getUsername()));
  }

  /** Confirms provisioning and activates MFA for the user. */
  @Transactional
  public UserDto enableMfa(String id, String code) {
    User user = requireUser(id);
    if (user.getMfaSecret() == null || !totpService.verify(user.getMfaSecret(), code)) {
      throw ApiException.badRequest("invalid_code", "Invalid code; MFA not enabled");
    }
    user.setMfaEnabled(true);
    userRepository.save(user);
    auditLogService.record(
        "USER_MFA_ENABLE",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("username", user.getUsername()));
    return getUser(id);
  }

  @Transactional
  public UserDto disableMfa(String id) {
    User user = requireUser(id);
    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    userRepository.save(user);
    auditLogService.record(
        "USER_MFA_DISABLE",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("username", user.getUsername()));
    return getUser(id);
  }

  // ---- settings passthrough ----------------------------------------------

  @Transactional(readOnly = true)
  public Map<String, Object> userSettings(String id) {
    requireUser(id);
    return settingsService.userSettings(id);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> effectiveSettings(String id) {
    requireUser(id);
    return settingsService.effectiveForUser(id);
  }

  @Transactional
  public Map<String, Object> setUserSetting(String id, String key, Object value) {
    requireUser(id);
    settingsService.setUserSetting(id, key, value);
    auditLogService.record(
        "USER_SETTING_SET", AuditLogService.CAT_USER, id, "user", Map.of("key", key));
    return settingsService.userSettings(id);
  }

  @Transactional
  public Map<String, Object> deleteUserSetting(String id, String key) {
    requireUser(id);
    settingsService.deleteUserSetting(id, key);
    auditLogService.record(
        "USER_SETTING_DELETE", AuditLogService.CAT_USER, id, "user", Map.of("key", key));
    return settingsService.userSettings(id);
  }

  // ---- helpers ------------------------------------------------------------

  private void setMemberships(String userId, List<String> groupIds) {
    if (groupIds == null) {
      return;
    }
    memberRepository.deleteAll(memberRepository.findById_UserId(userId));
    for (String groupId : groupIds) {
      groupRepository
          .findById(groupId)
          .orElseThrow(
              () -> ApiException.notFound("group_not_found", "Group not found: " + groupId));
      memberRepository.save(new GroupMember(groupId, userId));
    }
  }

  private User requireUser(String id) {
    return userRepository
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
  }

  private long countAdmins() {
    return userRepository.countByRole(User.Role.ADMIN);
  }

  /** RESELLER accounts cannot create or promote other accounts to ADMIN. */
  private void assertCanAssignRole(User actor, User.Role role) {
    if (role == User.Role.ADMIN && actor.getRole() != User.Role.ADMIN) {
      throw ApiException.forbidden("forbidden", "Only admins can grant the ADMIN role");
    }
    if (role == User.Role.RESELLER && actor.getRole() != User.Role.ADMIN) {
      throw ApiException.forbidden("forbidden", "Only admins can grant the RESELLER role");
    }
  }
}
