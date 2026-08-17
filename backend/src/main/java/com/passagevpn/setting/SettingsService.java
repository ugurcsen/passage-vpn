package com.passagevpn.setting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.common.ApiException;
import com.passagevpn.group.Group;
import com.passagevpn.group.GroupMember;
import com.passagevpn.group.GroupMemberRepository;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.network.ServerSetting;
import com.passagevpn.network.ServerSettingRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central settings store. Values are JSON-encoded strings in TEXT columns. Effective resolution
 * follows user &gt; group &gt; server: {@link #effectiveForUser}.
 */
@Service
public class SettingsService {

  private final ServerSettingRepository serverRepository;
  private final UserSettingRepository userRepository;
  private final GroupSettingRepository groupRepository;
  private final GroupMemberRepository memberRepository;
  private final GroupRepository groupRepository_;
  private final ObjectMapper objectMapper;

  /**
   * Server-level settings are read on every auth/settings resolution but written rarely, so the
   * decoded map is cached and invalidated on write. Writes within the same transaction still see a
   * fresh read because the cache is cleared before the save commits.
   */
  private volatile Map<String, Object> serverSettingsCache;

  public SettingsService(
      ServerSettingRepository serverRepository,
      UserSettingRepository userRepository,
      GroupSettingRepository groupRepository,
      GroupMemberRepository memberRepository,
      GroupRepository groupRepository_,
      ObjectMapper objectMapper) {
    this.serverRepository = serverRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.memberRepository = memberRepository;
    this.groupRepository_ = groupRepository_;
    this.objectMapper = objectMapper;
  }

  // ---- server level -------------------------------------------------------

  @Transactional(readOnly = true)
  public Map<String, Object> serverSettings() {
    Map<String, Object> cached = serverSettingsCache;
    if (cached != null) {
      return cached;
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (ServerSetting setting : serverRepository.findAll()) {
      map.put(setting.getKey(), decode(setting.getValue()));
    }
    // Order-preserving but immutable so no caller can corrupt the shared cache.
    serverSettingsCache = java.util.Collections.unmodifiableMap(map);
    return serverSettingsCache;
  }

  @Transactional
  public void setServerSetting(String key, Object value) {
    ServerSetting setting =
        serverRepository.findById(key).orElseGet(() -> ServerSetting.builder().key(key).build());
    setting.setValue(encode(value));
    serverRepository.save(setting);
    serverSettingsCache = null;
  }

  @Transactional
  public void deleteServerSetting(String key) {
    serverRepository.findById(key).ifPresent(serverRepository::delete);
    serverSettingsCache = null;
  }

  // ---- group level --------------------------------------------------------

  @Transactional(readOnly = true)
  public Map<String, Object> groupSettings(String groupId) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (GroupSetting setting : groupRepository.findByGroupId(groupId)) {
      map.put(setting.getKey(), decode(setting.getValue()));
    }
    return map;
  }

  @Transactional
  public void setGroupSetting(String groupId, String key, Object value) {
    GroupSetting setting =
        groupRepository
            .findByGroupIdAndKey(groupId, key)
            .orElseGet(
                () ->
                    GroupSetting.builder()
                        .id(java.util.UUID.randomUUID().toString())
                        .groupId(groupId)
                        .key(key)
                        .build());
    setting.setValue(encode(value));
    groupRepository.save(setting);
  }

  @Transactional
  public void deleteGroupSetting(String groupId, String key) {
    groupRepository.deleteByGroupIdAndKey(groupId, key);
  }

  // ---- user level ---------------------------------------------------------

  @Transactional(readOnly = true)
  public Map<String, Object> userSettings(String userId) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (UserSetting setting : userRepository.findByUserId(userId)) {
      map.put(setting.getKey(), decode(setting.getValue()));
    }
    return map;
  }

  @Transactional
  public void setUserSetting(String userId, String key, Object value) {
    UserSetting setting =
        userRepository
            .findByUserIdAndKey(userId, key)
            .orElseGet(
                () ->
                    UserSetting.builder()
                        .id(java.util.UUID.randomUUID().toString())
                        .userId(userId)
                        .key(key)
                        .build());
    setting.setValue(encode(value));
    userRepository.save(setting);
  }

  @Transactional
  public void deleteUserSetting(String userId, String key) {
    userRepository.deleteByUserIdAndKey(userId, key);
  }

  // ---- resolution ---------------------------------------------------------

  /**
   * Effective settings for a user: server defaults overridden by the user's most specific group,
   * then by direct user settings. For the group hierarchy, a parent group's value is overridden by
   * the child group's value.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> effectiveForUser(String userId) {
    Map<String, Object> result = new LinkedHashMap<>(serverSettings());

    List<String> groupChain = groupChainForUser(userId);
    for (String groupId : groupChain) {
      result.putAll(groupSettings(groupId));
    }
    result.putAll(userSettings(userId));
    return result;
  }

  /**
   * Effective settings for many users in one pass. Batches every read (memberships, group and user
   * settings, group hierarchy) so list views no longer issue per-user queries.
   */
  @Transactional(readOnly = true)
  public Map<String, Map<String, Object>> effectiveForUsers(Collection<String> userIds) {
    Map<String, Map<String, Object>> result = new HashMap<>();
    if (userIds == null || userIds.isEmpty()) {
      return result;
    }
    List<String> ids = userIds.stream().distinct().toList();
    Map<String, Object> server = serverSettings();

    Map<String, Group> groupById =
        groupRepository_.findAll().stream().collect(Collectors.toMap(Group::getId, g -> g));
    Map<String, List<GroupMember>> memberships =
        memberRepository.findById_UserIdIn(ids).stream()
            .collect(Collectors.groupingBy(m -> m.getId().getUserId()));
    Set<String> involvedGroupIds = new LinkedHashSet<>();
    for (List<GroupMember> members : memberships.values()) {
      for (GroupMember member : members) {
        collectChain(member.getId().getGroupId(), groupById, new ArrayList<>(), involvedGroupIds);
      }
    }
    Map<String, List<GroupSetting>> groupSettings =
        groupRepository.findByGroupIdIn(involvedGroupIds).stream()
            .collect(Collectors.groupingBy(GroupSetting::getGroupId));
    Map<String, List<UserSetting>> userSettings =
        userRepository.findByUserIdIn(ids).stream()
            .collect(Collectors.groupingBy(UserSetting::getUserId));

    for (String userId : ids) {
      Map<String, Object> effective = new LinkedHashMap<>(server);
      List<String> chain = new ArrayList<>();
      Set<String> visited = new LinkedHashSet<>();
      for (GroupMember member : memberships.getOrDefault(userId, List.of())) {
        collectChain(member.getId().getGroupId(), groupById, chain, visited);
      }
      for (String groupId : chain) {
        for (GroupSetting setting : groupSettings.getOrDefault(groupId, List.of())) {
          effective.put(setting.getKey(), decode(setting.getValue()));
        }
      }
      for (UserSetting setting : userSettings.getOrDefault(userId, List.of())) {
        effective.put(setting.getKey(), decode(setting.getValue()));
      }
      result.put(userId, effective);
    }
    return result;
  }

  /** Groups that apply to a user, most specific (child) first, walking up ancestors. */
  @Transactional(readOnly = true)
  public List<String> groupChainForUser(String userId) {
    Map<String, Group> groupById =
        groupRepository_.findAll().stream().collect(Collectors.toMap(Group::getId, g -> g));
    List<String> chain = new ArrayList<>();
    Set<String> visited = new LinkedHashSet<>();
    for (var member : memberRepository.findById_UserId(userId)) {
      collectChain(member.getId().getGroupId(), groupById, chain, visited);
    }
    return chain;
  }

  /** Walks a group and its ancestors, appending to {@code chain} in child-first order. */
  private void collectChain(
      String groupId, Map<String, Group> groupById, List<String> chain, Set<String> visited) {
    if (groupId == null || visited.contains(groupId)) {
      return;
    }
    visited.add(groupId);
    chain.add(groupId);
    Group group = groupById.get(groupId);
    if (group != null && group.getParentId() != null) {
      collectChain(group.getParentId(), groupById, chain, visited);
    }
  }

  // ---- helpers ------------------------------------------------------------

  private String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw ApiException.badRequest("invalid_setting", "Setting value is not JSON-serializable");
    }
  }

  private Object decode(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, Object.class);
    } catch (Exception e) {
      return raw;
    }
  }
}
