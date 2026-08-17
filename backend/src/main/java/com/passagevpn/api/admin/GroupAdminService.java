package com.passagevpn.api.admin;

import com.passagevpn.audit.AuditLogService;
import com.passagevpn.ccd.CcdService;
import com.passagevpn.common.ApiException;
import com.passagevpn.group.Group;
import com.passagevpn.group.GroupMember;
import com.passagevpn.group.GroupMemberRepository;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.group.GroupScope;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin operations over groups: CRUD, membership, inherited settings. */
@Service
public class GroupAdminService {

  private final GroupRepository groupRepository;
  private final GroupMemberRepository memberRepository;
  private final UserRepository userRepository;
  private final GroupScope groupScope;
  private final SettingsService settingsService;
  private final CcdService ccdService;
  private final AuditLogService auditLogService;

  public GroupAdminService(
      GroupRepository groupRepository,
      GroupMemberRepository memberRepository,
      UserRepository userRepository,
      GroupScope groupScope,
      SettingsService settingsService,
      CcdService ccdService,
      AuditLogService auditLogService) {
    this.groupRepository = groupRepository;
    this.memberRepository = memberRepository;
    this.userRepository = userRepository;
    this.groupScope = groupScope;
    this.settingsService = settingsService;
    this.ccdService = ccdService;
    this.auditLogService = auditLogService;
  }

  @Transactional(readOnly = true)
  public List<GroupDto> listGroups(User actor) {
    Set<String> scoped = groupScope.scopedGroupIds(actor);
    return groupRepository.findAll().stream()
        .filter(group -> scoped == null || scoped.contains(group.getId()))
        .sorted(java.util.Comparator.comparing(Group::getName, String.CASE_INSENSITIVE_ORDER))
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public GroupDto createGroup(User actor, GroupCreateRequest request) {
    if (groupRepository.existsByName(request.name().trim())) {
      throw ApiException.conflict("group_name_taken", "A group with this name already exists");
    }
    if (!groupScope.canCreateSubgroup(actor, request.parentId())) {
      throw ApiException.forbidden("forbidden", "Group out of scope");
    }
    if (request.parentId() != null) {
      requireGroup(request.parentId());
    }
    Group group =
        Group.builder()
            .id(UUID.randomUUID().toString())
            .name(request.name().trim())
            .parentId(request.parentId())
            .description(request.description())
            .createdAt(Instant.now())
            .build();
    groupRepository.save(group);
    auditLogService.record(
        "GROUP_CREATE",
        AuditLogService.CAT_GROUP,
        group.getId(),
        "group",
        Map.of("name", group.getName()));
    return toDto(group);
  }

  @Transactional
  public GroupDto updateGroup(User actor, String id, GroupUpdateRequest request) {
    Group group = requireGroup(id);
    assertCanManageGroup(actor, group);
    if (request.name() != null
        && !request.name().isBlank()
        && !request.name().equals(group.getName())) {
      if (groupRepository.existsByName(request.name().trim())) {
        throw ApiException.conflict("group_name_taken", "A group with this name already exists");
      }
      group.setName(request.name().trim());
    }
    if (request.description() != null) {
      group.setDescription(request.description());
    }
    groupRepository.save(group);
    auditLogService.record(
        "GROUP_UPDATE",
        AuditLogService.CAT_GROUP,
        group.getId(),
        "group",
        Map.of("name", group.getName()));
    return toDto(group);
  }

  @Transactional
  public void deleteGroup(User actor, String id) {
    Group group = requireGroup(id);
    assertCanManageGroup(actor, group);
    if (!groupScope.isAdmin(actor) && group.getParentId() == null) {
      throw ApiException.forbidden(
          "cannot_delete_root", "GROUP_ADMIN cannot delete the root group they manage");
    }
    memberRepository.deleteById_GroupId(id);
    settingsService
        .groupSettings(id)
        .keySet()
        .forEach(key -> settingsService.deleteGroupSetting(id, key));
    groupRepository.deleteById(id);
    auditLogService.record(
        "GROUP_DELETE", AuditLogService.CAT_GROUP, id, "group", Map.of("name", group.getName()));
  }

  @Transactional
  public GroupDto setMembers(User actor, String id, List<String> userIds) {
    Group group = requireGroup(id);
    assertCanManageGroup(actor, group);
    memberRepository.deleteById_GroupId(id);
    for (String userId : userIds) {
      userRepository
          .findById(userId)
          .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found: " + userId));
      memberRepository.save(new GroupMember(id, userId));
    }
    auditLogService.record(
        "GROUP_MEMBERS_SET",
        AuditLogService.CAT_GROUP,
        id,
        "group",
        Map.of("memberCount", userIds.size()));
    return toDto(requireGroup(id));
  }

  @Transactional(readOnly = true)
  public List<String> memberIds(User actor, String id) {
    requireGroup(id);
    assertCanManageGroup(actor, requireGroup(id));
    return memberRepository.findById_GroupId(id).stream().map(m -> m.getId().getUserId()).toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> groupSettings(User actor, String id) {
    assertCanManageGroup(actor, requireGroup(id));
    return settingsService.groupSettings(id);
  }

  @Transactional
  public Map<String, Object> setGroupSetting(User actor, String id, String key, Object value) {
    assertCanManageGroup(actor, requireGroup(id));
    settingsService.setGroupSetting(id, key, value);
    auditLogService.record(
        "GROUP_SETTING_SET", AuditLogService.CAT_GROUP, id, "group", Map.of("key", key));
    return settingsService.groupSettings(id);
  }

  @Transactional(readOnly = true)
  public String staticIpPool(User actor, String id) {
    assertCanManageGroup(actor, requireGroup(id));
    Object pool = settingsService.groupSettings(id).get(SettingKeys.STATIC_IP_POOL);
    return pool == null ? null : pool.toString();
  }

  @Transactional
  public String setStaticIpPool(User actor, String id, String pool) {
    assertCanManageGroup(actor, requireGroup(id));
    ccdService.validatePool(pool);
    if (pool == null || pool.isBlank()) {
      settingsService.deleteGroupSetting(id, SettingKeys.STATIC_IP_POOL);
    } else {
      settingsService.setGroupSetting(id, SettingKeys.STATIC_IP_POOL, pool.trim());
    }
    auditLogService.record(
        "GROUP_STATIC_IP_POOL_SET",
        AuditLogService.CAT_GROUP,
        id,
        "group",
        Map.of("pool", pool == null ? "" : pool));
    return staticIpPool(actor, id);
  }

  @Transactional(readOnly = true)
  public String staticIpv6Pool(User actor, String id) {
    assertCanManageGroup(actor, requireGroup(id));
    Object pool = settingsService.groupSettings(id).get(SettingKeys.STATIC_IPV6_POOL);
    return pool == null ? null : pool.toString();
  }

  @Transactional
  public String setStaticIpv6Pool(User actor, String id, String pool) {
    assertCanManageGroup(actor, requireGroup(id));
    ccdService.validateIpv6Pool(pool);
    if (pool == null || pool.isBlank()) {
      settingsService.deleteGroupSetting(id, SettingKeys.STATIC_IPV6_POOL);
    } else {
      settingsService.setGroupSetting(id, SettingKeys.STATIC_IPV6_POOL, pool.trim());
    }
    auditLogService.record(
        "GROUP_STATIC_IPV6_POOL_SET",
        AuditLogService.CAT_GROUP,
        id,
        "group",
        Map.of("pool", pool == null ? "" : pool));
    return staticIpv6Pool(actor, id);
  }

  @Transactional
  public Map<String, Object> deleteGroupSetting(User actor, String id, String key) {
    assertCanManageGroup(actor, requireGroup(id));
    settingsService.deleteGroupSetting(id, key);
    auditLogService.record(
        "GROUP_SETTING_DELETE", AuditLogService.CAT_GROUP, id, "group", Map.of("key", key));
    return settingsService.groupSettings(id);
  }

  private Group requireGroup(String id) {
    return groupRepository
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("group_not_found", "Group not found"));
  }

  private void assertCanManageGroup(User actor, Group group) {
    if (!groupScope.managesGroup(actor, group.getId())) {
      throw ApiException.forbidden("forbidden", "Group out of scope");
    }
  }

  private GroupDto toDto(Group group) {
    return new GroupDto(
        group.getId(),
        group.getName(),
        group.getParentId(),
        group.getDescription(),
        memberRepository.findById_GroupId(group.getId()).size(),
        group.getCreatedAt());
  }
}
