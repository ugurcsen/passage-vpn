package com.opnl.vpn.setting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.network.ServerSetting;
import com.opnl.vpn.network.ServerSettingRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    Map<String, Object> map = new LinkedHashMap<>();
    for (ServerSetting setting : serverRepository.findAll()) {
      map.put(setting.getKey(), decode(setting.getValue()));
    }
    return map;
  }

  @Transactional
  public void setServerSetting(String key, Object value) {
    ServerSetting setting =
        serverRepository.findById(key).orElseGet(() -> ServerSetting.builder().key(key).build());
    setting.setValue(encode(value));
    serverRepository.save(setting);
  }

  @Transactional
  public void deleteServerSetting(String key) {
    serverRepository.deleteById(key);
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

  /** Groups that apply to a user, most specific (child) first, walking up ancestors. */
  @Transactional(readOnly = true)
  public List<String> groupChainForUser(String userId) {
    List<String> chain = new ArrayList<>();
    List<String> visited = new ArrayList<>();
    for (var member : memberRepository.findById_UserId(userId)) {
      collectAncestors(member.getId().getGroupId(), chain, visited);
    }
    return chain;
  }

  private void collectAncestors(String groupId, List<String> chain, List<String> visited) {
    if (groupId == null || visited.contains(groupId)) {
      return;
    }
    visited.add(groupId);
    chain.add(groupId);
    groupRepository_
        .findById(groupId)
        .map(com.opnl.vpn.group.Group::getParentId)
        .ifPresent(parent -> collectAncestors(parent, chain, visited));
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
