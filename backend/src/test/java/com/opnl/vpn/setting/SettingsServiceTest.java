package com.opnl.vpn.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.network.ServerSetting;
import com.opnl.vpn.network.ServerSettingRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    when(groupRepository_.findById("g1"))
        .thenReturn(Optional.of(Group.builder().id("g1").name("sales").parentId("g0").build()));
    when(groupRepository_.findById("g0"))
        .thenReturn(Optional.of(Group.builder().id("g0").name("root").build()));
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
    when(groupRepository_.findById("g1"))
        .thenReturn(Optional.of(Group.builder().id("g1").name("sales").parentId("g0").build()));
    when(groupRepository_.findById("g0"))
        .thenReturn(Optional.of(Group.builder().id("g0").name("root").build()));

    assertThat(service.groupChainForUser("u1")).containsExactly("g1", "g0");
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
