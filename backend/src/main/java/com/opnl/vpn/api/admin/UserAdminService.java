package com.opnl.vpn.api.admin;

import com.opnl.vpn.access.AccessRuleService;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.auth.TotpService;
import com.opnl.vpn.ccd.CcdService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupAdminAssignment;
import com.opnl.vpn.group.GroupAdminAssignmentRepository;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.group.GroupScope;
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
  private final GroupAdminAssignmentRepository adminAssignmentRepository;
  private final GroupScope groupScope;
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
      GroupAdminAssignmentRepository adminAssignmentRepository,
      GroupScope groupScope,
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
    this.adminAssignmentRepository = adminAssignmentRepository;
    this.groupScope = groupScope;
    this.passwordEncoder = passwordEncoder;
    this.totpService = totpService;
    this.settingsService = settingsService;
    this.ccdService = ccdService;
    this.certService = certService;
    this.accessRuleService = accessRuleService;
    this.auditLogService = auditLogService;
  }

  @Transactional(readOnly = true)
  public List<UserDto> listUsers(User actor) {
    return listUsers(actor, null);
  }

  @Transactional(readOnly = true)
  public List<UserDto> listUsers(User actor, String search) {
    List<User> users = userRepository.findAll();
    Map<String, List<GroupMember>> byUser =
        memberRepository.findAll().stream()
            .collect(Collectors.groupingBy(m -> m.getId().getUserId()));
    Map<String, String> groupNames =
        groupRepository.findAll().stream().collect(Collectors.toMap(Group::getId, Group::getName));
    Map<String, List<GroupAdminAssignment>> byAdmin =
        adminAssignmentRepository.findAll().stream()
            .collect(Collectors.groupingBy(a -> a.getId().getUserId()));
    // Resolve effective settings for every user in a single batched pass (no per-user queries).
    Map<String, Map<String, Object>> effectiveByUser =
        settingsService.effectiveForUsers(users.stream().map(User::getId).toList());
    String needle = search == null ? "" : search.trim().toLowerCase();
    java.util.Set<String> scopedIds = groupScope.scopedUserIds(actor);
    return users.stream()
        .filter(user -> groupScope.isAdmin(actor) || scopedIds.contains(user.getId()))
        .filter(user -> matches(user, needle))
        .map(
            user -> {
              List<String> names =
                  byUser.getOrDefault(user.getId(), List.of()).stream()
                      .map(m -> groupNames.get(m.getId().getGroupId()))
                      .filter(name -> name != null)
                      .sorted()
                      .toList();
              List<String> adminIds =
                  byAdmin.getOrDefault(user.getId(), List.of()).stream()
                      .map(a -> a.getId().getGroupId())
                      .sorted()
                      .toList();
              List<String> adminNames =
                  adminIds.stream().map(groupNames::get).filter(name -> name != null).toList();
              Map<String, Object> effective = effectiveByUser.getOrDefault(user.getId(), Map.of());
              boolean mustChange =
                  Boolean.TRUE.equals(effective.get(SettingKeys.MUST_CHANGE_PASSWORD));
              boolean requireMfa = Boolean.TRUE.equals(effective.get(SettingKeys.REQUIRE_MFA));
              return UserDto.from(user, requireMfa, mustChange, names, adminIds, adminNames);
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
  public UserDto getUser(User actor, String id) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
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
    List<GroupAdminAssignment> assignments = adminAssignmentRepository.findById_UserId(id);
    List<String> adminIds = assignments.stream().map(a -> a.getId().getGroupId()).sorted().toList();
    List<String> adminNames =
        adminIds.stream()
            .map(groupId -> groupRepository.findById(groupId).map(Group::getName).orElse(null))
            .filter(name -> name != null)
            .toList();
    boolean mustChange =
        Boolean.TRUE.equals(settingsService.userSettings(id).get(SettingKeys.MUST_CHANGE_PASSWORD));
    return UserDto.from(user, mfaRequired(user), mustChange, names, adminIds, adminNames);
  }

  @Transactional
  public UserDto createUser(User actor, UserCreateRequest request) {
    if (userRepository.existsByUsername(request.username().trim())) {
      throw ApiException.conflict("username_taken", "Username already exists");
    }
    User.Role role = request.role() == null ? User.Role.USER : request.role();
    assertCanAssignRole(actor, role);
    if (role == User.Role.GROUP_ADMIN
        && (request.adminGroupIds() == null || request.adminGroupIds().isEmpty())) {
      throw ApiException.badRequest(
          "admin_groups_required", "GROUP_ADMIN requires at least one managed group");
    }
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
    setMemberships(actor, user.getId(), request.groupIds());
    setAdminAssignments(user.getId(), role, request.adminGroupIds());
    auditLogService.record(
        "USER_CREATE",
        AuditLogService.CAT_USER,
        user.getId(),
        "user",
        Map.of("username", user.getUsername(), "role", role.name()));
    return getUser(actor, user.getId());
  }

  @Transactional
  public UserDto updateUser(User actor, String id, UserUpdateRequest request) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    boolean roleChanged = request.role() != null && request.role() != user.getRole();
    if (roleChanged) {
      assertCanAssignRole(actor, request.role());
      if (user.getRole() == User.Role.ADMIN && countAdmins() <= 1) {
        throw ApiException.badRequest("last_admin", "Cannot change the role of the last admin");
      }
      if (request.role() == User.Role.GROUP_ADMIN
          && (request.adminGroupIds() == null || request.adminGroupIds().isEmpty())) {
        throw ApiException.badRequest(
            "admin_groups_required", "GROUP_ADMIN requires at least one managed group");
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
      setMemberships(actor, user.getId(), request.groupIds());
    }
    if (roleChanged || request.adminGroupIds() != null) {
      setAdminAssignments(user.getId(), user.getRole(), request.adminGroupIds());
    }
    userRepository.save(user);
    auditLogService.record(
        "USER_UPDATE",
        AuditLogService.CAT_USER,
        user.getId(),
        "user",
        Map.of("username", user.getUsername()));
    return getUser(actor, user.getId());
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
    assertCanManageUser(actor, user);
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
    adminAssignmentRepository.deleteAll(adminAssignmentRepository.findById_UserId(id));
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
        case BAN -> setBanned(actor, id, true);
        case UNBAN -> setBanned(actor, id, false);
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
  public void resetPassword(User actor, String id, String newPassword) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
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
  public UserDto setBanned(User actor, String id, boolean banned) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
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
    return getUser(actor, id);
  }

  @Transactional
  public UserDto setStaticIp(User actor, String id, String staticIp) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    ccdService.setStaticIp(id, staticIp);
    auditLogService.record(
        "USER_STATIC_IP_SET", AuditLogService.CAT_USER, id, "user", Map.of("staticIp", staticIp));
    return getUser(actor, id);
  }

  /** Allocates the next free static IP from the user's group pool. */
  @Transactional
  public UserDto allocateStaticIp(User actor, String id) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    ccdService.allocateFromGroupPool(id);
    auditLogService.record("USER_STATIC_IP_ALLOCATE", AuditLogService.CAT_USER, id, "user", null);
    return getUser(actor, id);
  }

  @Transactional
  public UserDto clearStaticIp(User actor, String id) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    ccdService.clearStaticIp(id);
    auditLogService.record("USER_STATIC_IP_CLEAR", AuditLogService.CAT_USER, id, "user", null);
    return getUser(actor, id);
  }

  @Transactional
  public UserDto setStaticIpv6(User actor, String id, String staticIpv6) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    ccdService.setStaticIpv6(id, staticIpv6);
    auditLogService.record(
        "USER_STATIC_IPV6_SET",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("staticIpv6", staticIpv6));
    return getUser(actor, id);
  }

  /** Allocates the next free static IPv6 from the user's group pool. */
  @Transactional
  public UserDto allocateStaticIpv6(User actor, String id) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    ccdService.allocateIpv6FromGroupPool(id);
    auditLogService.record("USER_STATIC_IPV6_ALLOCATE", AuditLogService.CAT_USER, id, "user", null);
    return getUser(actor, id);
  }

  @Transactional
  public UserDto clearStaticIpv6(User actor, String id) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    ccdService.clearStaticIpv6(id);
    auditLogService.record("USER_STATIC_IPV6_CLEAR", AuditLogService.CAT_USER, id, "user", null);
    return getUser(actor, id);
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
  public UserDto enableMfa(User actor, String id, String code) {
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
    return getUser(actor, id);
  }

  @Transactional
  public UserDto disableMfa(User actor, String id) {
    User user = requireUser(id);
    if (mfaRequired(user)) {
      throw ApiException.forbidden(
          "mfa_required", "Two-factor authentication is required by policy and cannot be disabled");
    }
    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    userRepository.save(user);
    auditLogService.record(
        "USER_MFA_DISABLE",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("username", user.getUsername()));
    return getUser(actor, id);
  }

  // ---- settings passthrough ----------------------------------------------

  @Transactional(readOnly = true)
  public Map<String, Object> userSettings(User actor, String id) {
    assertCanManageUser(actor, requireUser(id));
    return settingsService.userSettings(id);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> effectiveSettings(User actor, String id) {
    assertCanManageUser(actor, requireUser(id));
    return settingsService.effectiveForUser(id);
  }

  @Transactional
  public Map<String, Object> setUserSetting(User actor, String id, String key, Object value) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    settingsService.setUserSetting(id, key, value);
    auditLogService.record(
        "USER_SETTING_SET", AuditLogService.CAT_USER, id, "user", Map.of("key", key));
    return settingsService.userSettings(id);
  }

  @Transactional
  public Map<String, Object> deleteUserSetting(User actor, String id, String key) {
    User user = requireUser(id);
    assertCanManageUser(actor, user);
    settingsService.deleteUserSetting(id, key);
    auditLogService.record(
        "USER_SETTING_DELETE", AuditLogService.CAT_USER, id, "user", Map.of("key", key));
    return settingsService.userSettings(id);
  }

  // ---- helpers ------------------------------------------------------------

  private void setMemberships(User actor, String userId, List<String> groupIds) {
    if (groupIds == null) {
      return;
    }
    memberRepository.deleteAll(memberRepository.findById_UserId(userId));
    for (String groupId : groupIds) {
      if (!groupScope.managesGroup(actor, groupId)) {
        throw ApiException.forbidden("forbidden", "Group out of scope: " + groupId);
      }
      groupRepository
          .findById(groupId)
          .orElseThrow(
              () -> ApiException.notFound("group_not_found", "Group not found: " + groupId));
      memberRepository.save(new GroupMember(groupId, userId));
    }
  }

  private void setAdminAssignments(String userId, User.Role role, List<String> adminGroupIds) {
    adminAssignmentRepository.deleteAll(adminAssignmentRepository.findById_UserId(userId));
    if (role != User.Role.GROUP_ADMIN || adminGroupIds == null) {
      return;
    }
    for (String groupId : adminGroupIds) {
      groupRepository
          .findById(groupId)
          .orElseThrow(
              () -> ApiException.notFound("group_not_found", "Group not found: " + groupId));
      adminAssignmentRepository.save(new GroupAdminAssignment(userId, groupId));
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

  /** True when the server/group policy mandates MFA for this account. */
  private boolean mfaRequired(User user) {
    Map<String, Object> settings = settingsService.effectiveForUser(user.getId());
    return settings != null && Boolean.TRUE.equals(settings.get(SettingKeys.REQUIRE_MFA));
  }

  /**
   * Only ADMINS may grant ADMIN or GROUP_ADMIN; USER is grantable by anyone who manages the target.
   */
  private void assertCanAssignRole(User actor, User.Role role) {
    if (role == User.Role.ADMIN && actor.getRole() != User.Role.ADMIN) {
      throw ApiException.forbidden("forbidden", "Only admins can grant the ADMIN role");
    }
    if (role == User.Role.GROUP_ADMIN && actor.getRole() != User.Role.ADMIN) {
      throw ApiException.forbidden("forbidden", "Only admins can grant the GROUP_ADMIN role");
    }
  }

  /** ADMINS manage everyone; GROUP_ADMINS only manage USER accounts that belong to their scope. */
  private void assertCanManageUser(User actor, User target) {
    if (actor.getRole() == User.Role.ADMIN) {
      return;
    }
    if (target.getRole() != User.Role.USER || !groupScope.managesUser(actor, target.getId())) {
      throw ApiException.forbidden(
          "forbidden", "Only admins can manage ADMIN or GROUP_ADMIN accounts");
    }
  }
}
