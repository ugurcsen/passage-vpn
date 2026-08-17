package com.passagevpn.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.group.Group;
import com.passagevpn.group.GroupMember;
import com.passagevpn.group.GroupMemberRepository;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.network.ServerSetting;
import com.passagevpn.network.ServerSettingRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SettingsServiceTest {

  private ServerSettingRepository serverRepository;
  private UserSettingRepository userRepository;
  private GroupSettingRepository groupRepository;
  private GroupMemberRepository memberRepository;
  private GroupRepository groupRepository_;
  private SettingsService service;

  @BeforeEach
  void setUp() {
    serverRepository = mock(ServerSettingRepository.class);
    userRepository = mock(UserSettingRepository.class);
    groupRepository = mock(GroupSettingRepository.class);
    memberRepository = mock(GroupMemberRepository.class);
    groupRepository_ = mock(GroupRepository.class);
    service =
        new SettingsService(
            serverRepository,
            userRepository,
            groupRepository,
            memberRepository,
            groupRepository_,
            new ObjectMapper());
  }

  @Test
  void effectiveSettingsResolveServerThenGroupThenUser() {
    when(serverRepository.findAll())
        .thenReturn(
            List.of(
                ServerSetting.builder().key("max_connections").value("5").build(),
                ServerSetting.builder().key("dns_servers").value("\"1.1.1.1\"").build()));
    when(memberRepository.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g1", "u1")));
    when(groupRepository_.findAll())
        .thenReturn(
            List.of(
                Group.builder().id("g1").name("sales").parentId("g0").build(),
                Group.builder().id("g0").name("root").build()));
    when(groupRepository.findByGroupId("g1"))
        .thenReturn(List.of(GroupSetting.builder().key("max_connections").value("2").build()));
    when(groupRepository.findByGroupId("g0"))
        .thenReturn(
            List.of(GroupSetting.builder().key("dns_servers").value("\"8.8.8.8\"").build()));
    when(userRepository.findByUserId("u1"))
        .thenReturn(List.of(UserSetting.builder().key("dns_servers").value("\"9.9.9.9\"").build()));

    Map<String, Object> effective = service.effectiveForUser("u1");
    assertThat(effective.get("max_connections")).isEqualTo(2);
    assertThat(effective.get("dns_servers")).isEqualTo("9.9.9.9");
  }

  @Test
  void groupChainWalksAncestorsChildFirst() {
    when(memberRepository.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g1", "u1")));
    when(groupRepository_.findAll())
        .thenReturn(
            List.of(
                Group.builder().id("g1").name("sales").parentId("g0").build(),
                Group.builder().id("g0").name("root").build()));

    assertThat(service.groupChainForUser("u1")).containsExactly("g1", "g0");
  }

  @Test
  void effectiveForUsersResolvesAllUsersInOnePass() {
    when(serverRepository.findAll())
        .thenReturn(
            List.of(ServerSetting.builder().key("dns_servers").value("\"1.1.1.1\"").build()));
    when(memberRepository.findById_UserIdIn(List.of("u1", "u2")))
        .thenReturn(List.of(new GroupMember("g1", "u1")));
    when(groupRepository_.findAll())
        .thenReturn(
            List.of(
                Group.builder().id("g1").name("sales").parentId("g0").build(),
                Group.builder().id("g0").name("root").build()));
    when(groupRepository.findByGroupIdIn(java.util.Set.of("g1", "g0")))
        .thenReturn(
            List.of(
                GroupSetting.builder()
                    .groupId("g0")
                    .key("dns_servers")
                    .value("\"8.8.8.8\"")
                    .build(),
                GroupSetting.builder().groupId("g1").key("max_connections").value("4").build()));
    when(userRepository.findByUserIdIn(List.of("u1", "u2")))
        .thenReturn(
            List.of(
                UserSetting.builder().userId("u1").key("dns_servers").value("\"9.9.9.9\"").build(),
                UserSetting.builder()
                    .userId("u2")
                    .key("dns_servers")
                    .value("\"10.0.0.1\"")
                    .build()));

    Map<String, Map<String, Object>> effective = service.effectiveForUsers(List.of("u1", "u2"));

    assertThat(effective).containsKeys("u1", "u2");
    assertThat(effective.get("u1").get("dns_servers")).isEqualTo("9.9.9.9");
    assertThat(effective.get("u1").get("max_connections")).isEqualTo(4);
    assertThat(effective.get("u2").get("dns_servers")).isEqualTo("10.0.0.1");
    // u2 has no group membership, so the group-scoped setting must not leak in.
    assertThat(effective.get("u2")).doesNotContainKey("max_connections");
  }

  @Test
  void serverSettingsAreCachedUntilWritten() {
    when(serverRepository.findAll())
        .thenReturn(List.of(ServerSetting.builder().key("brand").value("\"OpenVPN\"").build()));

    Map<String, Object> first = service.serverSettings();
    Map<String, Object> second = service.serverSettings();
    assertThat(second).isSameAs(first);

    service.setServerSetting("brand", "Rebranded");
    // The write invalidates the cache; a fresh read reflects it.
    when(serverRepository.findAll())
        .thenReturn(List.of(ServerSetting.builder().key("brand").value("\"Rebranded\"").build()));
    assertThat(service.serverSettings().get("brand")).isEqualTo("Rebranded");
  }

  @Test
  void nonJsonSettingValuesDecodeToPlainStrings() {
    when(serverRepository.findAll())
        .thenReturn(List.of(ServerSetting.builder().key("brand").value("plain").build()));
    assertThat(service.serverSettings().get("brand")).isEqualTo("plain");
  }

  @Test
  void missingMembershipsProduceEmptyChain() {
    when(memberRepository.findById_UserId(anyString())).thenReturn(List.of());
    assertThat(service.groupChainForUser("u1")).isEmpty();
  }
}
