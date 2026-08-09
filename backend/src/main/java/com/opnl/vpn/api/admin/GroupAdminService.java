package com.opnl.vpn.api.admin;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin operations over groups: CRUD, membership, inherited settings. */
@Service
public class GroupAdminService {

  private final GroupRepository groupRepository;
  private final GroupMemberRepository memberRepository;
  private final UserRepository userRepository;
  private final SettingsService settingsService;

  public GroupAdminService(
      GroupRepository groupRepository,
      GroupMemberRepository memberRepository,
      UserRepository userRepository,
      SettingsService settingsService) {
    this.groupRepository = groupRepository;
    this.memberRepository = memberRepository;
    this.userRepository = userRepository;
    this.settingsService = settingsService;
  }

  @Transactional(readOnly = true)
  public List<GroupDto> listGroups() {
    return groupRepository.findAll().stream()
        .sorted(java.util.Comparator.comparing(Group::getName, String.CASE_INSENSITIVE_ORDER))
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public GroupDto createGroup(GroupCreateRequest request) {
    if (groupRepository.existsByName(request.name().trim())) {
      throw ApiException.conflict("group_name_taken", "A group with this name already exists");
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
    return toDto(group);
  }

  @Transactional
  public GroupDto updateGroup(String id, GroupUpdateRequest request) {
    Group group = requireGroup(id);
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
    return toDto(group);
  }

  @Transactional
  public void deleteGroup(String id) {
    requireGroup(id);
    memberRepository.deleteById_GroupId(id);
    settingsService
        .groupSettings(id)
        .keySet()
        .forEach(key -> settingsService.deleteGroupSetting(id, key));
    groupRepository.deleteById(id);
  }

  @Transactional
  public GroupDto setMembers(String id, List<String> userIds) {
    requireGroup(id);
    memberRepository.deleteById_GroupId(id);
    for (String userId : userIds) {
      userRepository
          .findById(userId)
          .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found: " + userId));
      memberRepository.save(new GroupMember(id, userId));
    }
    return toDto(requireGroup(id));
  }

  @Transactional(readOnly = true)
  public List<String> memberIds(String id) {
    requireGroup(id);
    return memberRepository.findById_GroupId(id).stream().map(m -> m.getId().getUserId()).toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> groupSettings(String id) {
    requireGroup(id);
    return settingsService.groupSettings(id);
  }

  @Transactional
  public Map<String, Object> setGroupSetting(String id, String key, Object value) {
    requireGroup(id);
    settingsService.setGroupSetting(id, key, value);
    return settingsService.groupSettings(id);
  }

  @Transactional
  public Map<String, Object> deleteGroupSetting(String id, String key) {
    requireGroup(id);
    settingsService.deleteGroupSetting(id, key);
    return settingsService.groupSettings(id);
  }

  private Group requireGroup(String id) {
    return groupRepository
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("group_not_found", "Group not found"));
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
