package com.passagevpn.api.admin;

import com.passagevpn.audit.AuditLogService;
import com.passagevpn.ccd.CcdService;
import com.passagevpn.common.ApiException;
import com.passagevpn.group.GroupScope;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin operations for managing a user's static IP/IPv6 assignments.
 *
 * <p>Delegates DTO construction to {@link UserAdminService#getUser} so that group membership, MFA
 * status, and settings are resolved consistently from a single code path.
 */
@Service
public class UserIpAdminService {

  private final UserRepository userRepository;
  private final GroupScope groupScope;
  private final CcdService ccdService;
  private final AuditLogService auditLogService;
  private final UserAdminService userAdminService;

  public UserIpAdminService(
      UserRepository userRepository,
      GroupScope groupScope,
      CcdService ccdService,
      AuditLogService auditLogService,
      UserAdminService userAdminService) {
    this.userRepository = userRepository;
    this.groupScope = groupScope;
    this.ccdService = ccdService;
    this.auditLogService = auditLogService;
    this.userAdminService = userAdminService;
  }

  // ---- IPv4 ---------------------------------------------------------------

  @Transactional
  public UserDto setStaticIp(User actor, String id, String staticIp) {
    requireUser(id);
    assertCanManageUser(actor, id);
    ccdService.setStaticIp(id, staticIp);
    auditLogService.record(
        "USER_STATIC_IP_SET", AuditLogService.CAT_USER, id, "user", Map.of("staticIp", staticIp));
    return userAdminService.getUser(actor, id);
  }

  @Transactional
  public UserDto allocateStaticIp(User actor, String id) {
    requireUser(id);
    assertCanManageUser(actor, id);
    ccdService.allocateFromGroupPool(id);
    auditLogService.record("USER_STATIC_IP_ALLOCATE", AuditLogService.CAT_USER, id, "user", null);
    return userAdminService.getUser(actor, id);
  }

  @Transactional
  public UserDto clearStaticIp(User actor, String id) {
    requireUser(id);
    assertCanManageUser(actor, id);
    ccdService.clearStaticIp(id);
    auditLogService.record("USER_STATIC_IP_CLEAR", AuditLogService.CAT_USER, id, "user", null);
    return userAdminService.getUser(actor, id);
  }

  // ---- IPv6 ---------------------------------------------------------------

  @Transactional
  public UserDto setStaticIpv6(User actor, String id, String staticIpv6) {
    requireUser(id);
    assertCanManageUser(actor, id);
    ccdService.setStaticIpv6(id, staticIpv6);
    auditLogService.record(
        "USER_STATIC_IPV6_SET",
        AuditLogService.CAT_USER,
        id,
        "user",
        Map.of("staticIpv6", staticIpv6));
    return userAdminService.getUser(actor, id);
  }

  @Transactional
  public UserDto allocateStaticIpv6(User actor, String id) {
    requireUser(id);
    assertCanManageUser(actor, id);
    ccdService.allocateIpv6FromGroupPool(id);
    auditLogService.record("USER_STATIC_IPV6_ALLOCATE", AuditLogService.CAT_USER, id, "user", null);
    return userAdminService.getUser(actor, id);
  }

  @Transactional
  public UserDto clearStaticIpv6(User actor, String id) {
    requireUser(id);
    assertCanManageUser(actor, id);
    ccdService.clearStaticIpv6(id);
    auditLogService.record("USER_STATIC_IPV6_CLEAR", AuditLogService.CAT_USER, id, "user", null);
    return userAdminService.getUser(actor, id);
  }

  // ---- helpers ------------------------------------------------------------

  private void requireUser(String id) {
    if (!userRepository.existsById(id)) {
      throw ApiException.notFound("user_not_found", "User not found");
    }
  }

  /** ADMINS manage everyone; GROUP_ADMINS only manage USER accounts that belong to their scope. */
  private void assertCanManageUser(User actor, String targetId) {
    if (actor.getRole() == User.Role.ADMIN) {
      return;
    }
    User target =
        userRepository
            .findById(targetId)
            .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
    if (target.getRole() != User.Role.USER || !groupScope.managesUser(actor, target.getId())) {
      throw ApiException.forbidden(
          "forbidden", "Only admins can manage ADMIN or GROUP_ADMIN accounts");
    }
  }
}
