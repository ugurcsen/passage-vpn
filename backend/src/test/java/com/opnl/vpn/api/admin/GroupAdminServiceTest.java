package com.opnl.vpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.ccd.CcdService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.group.GroupScope;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupAdminServiceTest {

  private GroupRepository groupRepository;
  private GroupMemberRepository memberRepository;
  private UserRepository userRepository;
  private SettingsService settingsService;
  private CcdService ccdService;
  private AuditLogService auditLogService;
  private GroupScope groupScope;
  private GroupAdminService service;

  private User admin() {
    return User.builder()
        .id("admin1")
        .username("admin")
        .role(User.Role.ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  private User groupAdmin() {
    return User.builder()
        .id("gadmin1")
        .username("gadmin")
        .role(User.Role.GROUP_ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  @BeforeEach
  void setUp() {
    groupRepository = mock(GroupRepository.class);
    memberRepository = mock(GroupMemberRepository.class);
    userRepository = mock(UserRepository.class);
    settingsService = mock(SettingsService.class);
    auditLogService = mock(AuditLogService.class);
    groupScope = mock(GroupScope.class);
    ccdService = mock(CcdService.class);
    service =
        new GroupAdminService(
            groupRepository,
            memberRepository,
            userRepository,
            groupScope,
            settingsService,
            ccdService,
            auditLogService);
    when(groupScope.isAdmin(any()))
        .thenAnswer(
            inv -> {
              User actor = inv.getArgument(0);
              return actor != null && actor.getRole() == User.Role.ADMIN;
            });
    when(groupScope.canCreateSubgroup(any(), any()))
        .thenAnswer(
            inv -> {
              User actor = inv.getArgument(0);
              return actor != null
                  && (actor.getRole() == User.Role.ADMIN || "g1".equals(inv.getArgument(1)));
            });
    when(groupScope.managesGroup(any(), any()))
        .thenAnswer(
            inv -> {
              User actor = inv.getArgument(0);
              return actor != null
                  && (actor.getRole() == User.Role.ADMIN || "g1".equals(inv.getArgument(1)));
            });
  }

  @Test
  void createGroupRecordsAudit() {
    when(groupRepository.existsByName("Engineering")).thenReturn(false);
    service.createGroup(
        admin(), new GroupCreateRequest("Engineering", null, "Engineering team access"));
    verify(auditLogService)
        .record(
            eq("GROUP_CREATE"),
            eq(AuditLogService.CAT_GROUP),
            anyString(),
            eq("group"),
            eq(Map.of("name", "Engineering")));
  }

  @Test
  void setMembersRecordsAudit() {
    Group group = Group.builder().id("g1").name("Engineering").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(userRepository.findById("u1"))
        .thenReturn(
            Optional.of(
                User.builder().id("u1").username("alice").createdAt(Instant.now()).build()));
    service.setMembers(admin(), "g1", List.of("u1"));
    verify(auditLogService)
        .record(
            eq("GROUP_MEMBERS_SET"),
            eq(AuditLogService.CAT_GROUP),
            eq("g1"),
            eq("group"),
            eq(Map.of("memberCount", 1)));
  }

  @Test
  void groupAdminCannotCreateNewRootGroup() {
    when(groupScope.canCreateSubgroup(any(), any())).thenReturn(false);
    when(groupRepository.existsByName("New")).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.createGroup(
                    groupAdmin(), new GroupCreateRequest("New", null, "root group")))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
  }

  @Test
  void groupAdminCanCreateSubgroupUnderManagedRoot() {
    when(groupRepository.existsByName("Nested")).thenReturn(false);
    when(groupRepository.findById("g1"))
        .thenReturn(
            Optional.of(Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build()));
    GroupDto dto = service.createGroup(groupAdmin(), new GroupCreateRequest("Nested", "g1", null));
    assertThat(dto.parentId()).isEqualTo("g1");
  }

  @Test
  void groupAdminCannotDeleteRootGroup() {
    Group root =
        Group.builder().id("g1").name("DevOps").parentId(null).createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(root));
    assertThatThrownBy(() -> service.deleteGroup(groupAdmin(), "g1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("cannot_delete_root"));
  }

  @Test
  void groupAdminCannotManageOutOfScopeGroup() {
    Group other = Group.builder().id("g9").name("Marketing").createdAt(Instant.now()).build();
    when(groupRepository.findById("g9")).thenReturn(Optional.of(other));
    assertThatThrownBy(
            () -> service.updateGroup(groupAdmin(), "g9", new GroupUpdateRequest("x", null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
  }

  @Test
  void listGroupsScopesToManagedGroups() {
    Group g1 = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    Group g9 = Group.builder().id("g9").name("Marketing").createdAt(Instant.now()).build();
    when(groupRepository.findAll()).thenReturn(List.of(g1, g9));
    when(groupScope.scopedGroupIds(any())).thenReturn(Set.of("g1"));

    List<GroupDto> dtos = service.listGroups(groupAdmin());

    assertThat(dtos).extracting(GroupDto::name).containsExactly("DevOps");
  }

  @Test
  void listGroupsSortsByName() {
    Group alpha = Group.builder().id("g1").name("Alpha").createdAt(Instant.now()).build();
    Group zebra = Group.builder().id("g2").name("Zebra").createdAt(Instant.now()).build();
    when(groupRepository.findAll()).thenReturn(List.of(zebra, alpha));
    when(groupScope.scopedGroupIds(any())).thenReturn(null);

    List<GroupDto> dtos = service.listGroups(admin());

    assertThat(dtos).extracting(GroupDto::name).containsExactly("Alpha", "Zebra");
  }

  @Test
  void listGroupsReportsMemberCounts() {
    Group g1 = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findAll()).thenReturn(List.of(g1));
    when(groupScope.scopedGroupIds(any())).thenReturn(null);
    when(memberRepository.findById_GroupId("g1"))
        .thenReturn(List.of(new GroupMember("g1", "u1"), new GroupMember("g1", "u2")));

    List<GroupDto> dtos = service.listGroups(admin());

    assertThat(dtos.get(0).memberCount()).isEqualTo(2);
  }

  @Test
  void createGroupRejectsDuplicateName() {
    when(groupRepository.existsByName("Dev")).thenReturn(true);
    assertThatThrownBy(
            () -> service.createGroup(admin(), new GroupCreateRequest("Dev", null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("group_name_taken"));
  }

  @Test
  void createGroupRejectsMissingParent() {
    when(groupRepository.existsByName("Dev")).thenReturn(false);
    assertThatThrownBy(
            () -> service.createGroup(admin(), new GroupCreateRequest("Dev", "g9", null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("group_not_found"));
  }

  @Test
  void createGroupTrimsName() {
    when(groupRepository.existsByName("Dev")).thenReturn(false);

    GroupDto dto = service.createGroup(admin(), new GroupCreateRequest("  Dev  ", null, null));

    assertThat(dto.name()).isEqualTo("Dev");
  }

  @Test
  void updateGroupRenamesAndSetsDescription() {
    Group group = Group.builder().id("g1").name("Old").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(groupRepository.existsByName("New")).thenReturn(false);

    GroupDto dto = service.updateGroup(admin(), "g1", new GroupUpdateRequest("New", "desc"));

    assertThat(group.getName()).isEqualTo("New");
    assertThat(dto.description()).isEqualTo("desc");
  }

  @Test
  void updateGroupRejectsDuplicateName() {
    Group group = Group.builder().id("g1").name("Old").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(groupRepository.existsByName("Other")).thenReturn(true);

    assertThatThrownBy(
            () -> service.updateGroup(admin(), "g1", new GroupUpdateRequest("Other", null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("group_name_taken"));
  }

  @Test
  void updateGroupBlankNameKeepsExisting() {
    Group group = Group.builder().id("g1").name("Old").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));

    service.updateGroup(admin(), "g1", new GroupUpdateRequest("   ", null));

    assertThat(group.getName()).isEqualTo("Old");
  }

  @Test
  void updateGroupThrowsWhenMissing() {
    assertThatThrownBy(() -> service.updateGroup(admin(), "g9", new GroupUpdateRequest("x", null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("group_not_found"));
  }

  @Test
  void deleteGroupRemovesMembershipsAndSettings() {
    Group group =
        Group.builder().id("g1").name("DevOps").parentId("p1").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1")).thenReturn(Map.of("k1", "v1", "k2", "v2"));

    service.deleteGroup(admin(), "g1");

    verify(memberRepository).deleteById_GroupId("g1");
    verify(settingsService).deleteGroupSetting("g1", "k1");
    verify(settingsService).deleteGroupSetting("g1", "k2");
    verify(groupRepository).deleteById("g1");
  }

  @Test
  void deleteGroupThrowsWhenMissing() {
    assertThatThrownBy(() -> service.deleteGroup(admin(), "g9"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("group_not_found"));
  }

  @Test
  void setMembersThrowsWhenUserMissing() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));

    assertThatThrownBy(() -> service.setMembers(admin(), "g1", List.of("u9")))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found"));
  }

  @Test
  void setMembersReplacesMembership() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(userRepository.findById("u1"))
        .thenReturn(
            Optional.of(
                User.builder().id("u1").username("alice").createdAt(Instant.now()).build()));
    when(userRepository.findById("u2"))
        .thenReturn(
            Optional.of(User.builder().id("u2").username("bob").createdAt(Instant.now()).build()));

    service.setMembers(admin(), "g1", List.of("u1", "u2"));

    verify(memberRepository).deleteById_GroupId("g1");
    verify(memberRepository).save(new GroupMember("g1", "u1"));
    verify(memberRepository).save(new GroupMember("g1", "u2"));
  }

  @Test
  void memberIdsReturnsUserIds() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(memberRepository.findById_GroupId("g1"))
        .thenReturn(List.of(new GroupMember("g1", "u2"), new GroupMember("g1", "u1")));

    assertThat(service.memberIds(admin(), "g1")).containsExactlyInAnyOrder("u1", "u2");
  }

  @Test
  void memberIdsThrowsWhenMissing() {
    assertThatThrownBy(() -> service.memberIds(admin(), "g9"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("group_not_found"));
  }

  @Test
  void groupSettingsDelegatesToSettingsService() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1")).thenReturn(Map.of("k", "v"));

    assertThat(service.groupSettings(admin(), "g1")).containsEntry("k", "v");
  }

  @Test
  void setGroupSettingPersistsAndReturnsUpdated() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1")).thenReturn(Map.of(SettingKeys.MAX_CONNECTIONS, 2));

    Map<String, Object> result =
        service.setGroupSetting(admin(), "g1", SettingKeys.MAX_CONNECTIONS, 2);

    verify(settingsService).setGroupSetting("g1", SettingKeys.MAX_CONNECTIONS, 2);
    assertThat(result).containsEntry(SettingKeys.MAX_CONNECTIONS, 2);
  }

  @Test
  void deleteGroupSettingDelegatesToSettingsService() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));

    service.deleteGroupSetting(admin(), "g1", SettingKeys.MAX_CONNECTIONS);

    verify(settingsService).deleteGroupSetting("g1", SettingKeys.MAX_CONNECTIONS);
  }

  @Test
  void staticIpPoolReadsGroupSetting() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1"))
        .thenReturn(Map.of(SettingKeys.STATIC_IP_POOL, "10.8.0.100-10.8.0.199"));

    assertThat(service.staticIpPool(admin(), "g1")).isEqualTo("10.8.0.100-10.8.0.199");
  }

  @Test
  void setStaticIpPoolWritesTrimmedSetting() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1"))
        .thenReturn(Map.of(SettingKeys.STATIC_IP_POOL, "10.8.0.100-10.8.0.199"));

    String pool = service.setStaticIpPool(admin(), "g1", " 10.8.0.100-10.8.0.199 ");

    verify(settingsService)
        .setGroupSetting("g1", SettingKeys.STATIC_IP_POOL, "10.8.0.100-10.8.0.199");
    assertThat(pool).isEqualTo("10.8.0.100-10.8.0.199");
  }

  @Test
  void setStaticIpPoolBlankClearsSetting() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1")).thenReturn(Map.of());

    service.setStaticIpPool(admin(), "g1", "  ");

    verify(settingsService).deleteGroupSetting("g1", SettingKeys.STATIC_IP_POOL);
    verify(settingsService, never())
        .setGroupSetting(eq("g1"), eq(SettingKeys.STATIC_IP_POOL), any());
  }

  @Test
  void setStaticIpPoolValidationFailurePropagates() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    org.mockito.Mockito.doThrow(ApiException.badRequest("invalid_ip_pool", "bad pool"))
        .when(ccdService)
        .validatePool("10.9.0.1-10.9.0.9");

    assertThatThrownBy(() -> service.setStaticIpPool(admin(), "g1", "10.9.0.1-10.9.0.9"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_ip_pool"));
  }

  @Test
  void staticIpv6PoolReadsGroupSetting() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1"))
        .thenReturn(Map.of(SettingKeys.STATIC_IPV6_POOL, "fd00:1::10-fd00:1::ff"));

    assertThat(service.staticIpv6Pool(admin(), "g1")).isEqualTo("fd00:1::10-fd00:1::ff");
  }

  @Test
  void setStaticIpv6PoolWritesTrimmedSetting() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1"))
        .thenReturn(Map.of(SettingKeys.STATIC_IPV6_POOL, "fd00:1::10-fd00:1::ff"));

    String pool = service.setStaticIpv6Pool(admin(), "g1", " fd00:1::10-fd00:1::ff ");

    verify(settingsService)
        .setGroupSetting("g1", SettingKeys.STATIC_IPV6_POOL, "fd00:1::10-fd00:1::ff");
    assertThat(pool).isEqualTo("fd00:1::10-fd00:1::ff");
  }

  @Test
  void setStaticIpv6PoolBlankClearsSetting() {
    Group group = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
    when(settingsService.groupSettings("g1")).thenReturn(Map.of());

    service.setStaticIpv6Pool(admin(), "g1", null);

    verify(settingsService).deleteGroupSetting("g1", SettingKeys.STATIC_IPV6_POOL);
    verify(settingsService, never())
        .setGroupSetting(eq("g1"), eq(SettingKeys.STATIC_IPV6_POOL), any());
  }
}
