package com.opnl.vpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.ccd.CcdService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.group.GroupScope;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupAdminServiceTest {

  private GroupRepository groupRepository;
  private GroupMemberRepository memberRepository;
  private UserRepository userRepository;
  private SettingsService settingsService;
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
    service =
        new GroupAdminService(
            groupRepository,
            memberRepository,
            userRepository,
            groupScope,
            settingsService,
            mock(CcdService.class),
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
}
