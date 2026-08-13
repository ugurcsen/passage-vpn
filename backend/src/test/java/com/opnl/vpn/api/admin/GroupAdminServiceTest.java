package com.opnl.vpn.api.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.ccd.CcdService;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
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
  private GroupAdminService service;

  @BeforeEach
  void setUp() {
    groupRepository = mock(GroupRepository.class);
    memberRepository = mock(GroupMemberRepository.class);
    userRepository = mock(UserRepository.class);
    settingsService = mock(SettingsService.class);
    auditLogService = mock(AuditLogService.class);
    service =
        new GroupAdminService(
            groupRepository,
            memberRepository,
            userRepository,
            settingsService,
            mock(CcdService.class),
            auditLogService);
  }

  @Test
  void createGroupRecordsAudit() {
    when(groupRepository.existsByName("Engineering")).thenReturn(false);
    service.createGroup(new GroupCreateRequest("Engineering", null, "Engineering team access"));
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
    service.setMembers("g1", List.of("u1"));
    verify(auditLogService)
        .record(
            eq("GROUP_MEMBERS_SET"),
            eq(AuditLogService.CAT_GROUP),
            eq("g1"),
            eq("group"),
            eq(Map.of("memberCount", 1)));
  }
}
