package com.opnl.vpn.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupScopeTest {

  private GroupRepository groupRepository;
  private GroupAdminAssignmentRepository assignmentRepository;
  private GroupMemberRepository memberRepository;
  private UserRepository userRepository;
  private GroupScope scope;

  private User admin() {
    return User.builder().id("admin1").username("root").role(User.Role.ADMIN).build();
  }

  private User groupAdmin() {
    return User.builder().id("gadmin1").username("gadmin").role(User.Role.GROUP_ADMIN).build();
  }

  private User plainUser() {
    return User.builder().id("u1").username("bob").role(User.Role.USER).build();
  }

  @BeforeEach
  void setUp() {
    groupRepository = mock(GroupRepository.class);
    assignmentRepository = mock(GroupAdminAssignmentRepository.class);
    memberRepository = mock(GroupMemberRepository.class);
    userRepository = mock(UserRepository.class);
    scope = new GroupScope(groupRepository, assignmentRepository, memberRepository, userRepository);
  }

  @Test
  void isAdminOnlyForAdminRole() {
    assertThat(scope.isAdmin(admin())).isTrue();
    assertThat(scope.isAdmin(groupAdmin())).isFalse();
    assertThat(scope.isAdmin(plainUser())).isFalse();
  }

  @Test
  void canManageRequiresAssignmentsForGroupAdmins() {
    assertThat(scope.canManage(admin())).isTrue();
    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    assertThat(scope.canManage(groupAdmin())).isTrue();
    when(assignmentRepository.findById_UserId("gadmin1")).thenReturn(List.of());
    assertThat(scope.canManage(groupAdmin())).isFalse();
    assertThat(scope.canManage(plainUser())).isFalse();
  }

  @Test
  void managedRootIdsIsNullForAdminAndEmptyForUser() {
    assertThat(scope.managedRootIds(admin())).isNull();
    assertThat(scope.managedRootIds(plainUser())).isEmpty();
    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    assertThat(scope.managedRootIds(groupAdmin())).containsExactly("g1");
  }

  @Test
  void scopedGroupIdsForAdminCoversEveryGroup() {
    when(groupRepository.findAll())
        .thenReturn(List.of(Group.builder().id("g1").build(), Group.builder().id("g2").build()));
    assertThat(scope.scopedGroupIds(admin())).containsExactlyInAnyOrder("g1", "g2");
  }

  @Test
  void scopedGroupIdsForGroupAdminUnionOfSubtrees() {
    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    when(groupRepository.findByParentId("g1"))
        .thenReturn(List.of(Group.builder().id("g2").parentId("g1").build()));
    when(groupRepository.findByParentId("g2"))
        .thenReturn(List.of(Group.builder().id("g3").parentId("g2").build()));
    when(groupRepository.findByParentId("g3")).thenReturn(List.of());

    assertThat(scope.scopedGroupIds(groupAdmin())).containsExactlyInAnyOrder("g1", "g2", "g3");
  }

  @Test
  void managesGroupIsTrueForAdminAndInScopeGroupAdmins() {
    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    when(groupRepository.findByParentId("g1"))
        .thenReturn(List.of(Group.builder().id("g2").parentId("g1").build()));

    assertThat(scope.managesGroup(admin(), "anything")).isTrue();
    assertThat(scope.managesGroup(groupAdmin(), "g1")).isTrue();
    assertThat(scope.managesGroup(groupAdmin(), "g2")).isTrue();
    assertThat(scope.managesGroup(groupAdmin(), "g9")).isFalse();
  }

  @Test
  void canCreateSubgroupRequiresManageableParentForGroupAdmins() {
    assertThat(scope.canCreateSubgroup(admin(), null)).isTrue();
    assertThat(scope.canCreateSubgroup(admin(), "g1")).isTrue();
    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    assertThat(scope.canCreateSubgroup(groupAdmin(), "g1")).isTrue();
    assertThat(scope.canCreateSubgroup(groupAdmin(), "g9")).isFalse();
    assertThat(scope.canCreateSubgroup(groupAdmin(), null)).isFalse();
  }

  @Test
  void managesUserResolvesViaMembershipInScopedGroups() {
    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    when(memberRepository.findById_UserId("t1")).thenReturn(List.of(new GroupMember("g1", "t1")));
    when(memberRepository.findById_UserId("t2")).thenReturn(List.of());

    assertThat(scope.managesUser(admin(), "t1")).isTrue();
    assertThat(scope.managesUser(groupAdmin(), "t1")).isTrue();
    assertThat(scope.managesUser(groupAdmin(), "t2")).isFalse();
    assertThat(scope.managesUser(plainUser(), "t1")).isFalse();
  }

  @Test
  void scopedUserIdsIsNullForAdminAndEmptyWhenNoScope() {
    assertThat(scope.scopedUserIds(admin())).isNull();
    assertThat(scope.scopedUserIds(plainUser())).isEmpty();
  }

  @Test
  void scopedUserIdsCollectsMembersOfScopedGroups() {
    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    when(groupRepository.findByParentId("g1")).thenReturn(List.of());
    when(memberRepository.findById_GroupIdIn(Set.of("g1")))
        .thenReturn(List.of(new GroupMember("g1", "t1"), new GroupMember("g1", "t2")));

    assertThat(scope.scopedUserIds(groupAdmin())).containsExactlyInAnyOrder("t1", "t2");
  }

  @Test
  void scopedUsernamesMapsIdsToUsernames() {
    assertThat(scope.scopedUsernames(admin())).isNull();
    when(assignmentRepository.findById_UserId("gadmin1")).thenReturn(List.of());
    assertThat(scope.scopedUsernames(groupAdmin())).isEmpty();

    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    when(memberRepository.findById_GroupIdIn(Set.of("g1")))
        .thenReturn(List.of(new GroupMember("g1", "t1")));
    when(userRepository.findAllById(Set.of("t1")))
        .thenReturn(List.of(User.builder().id("t1").username("carol").build()));

    assertThat(scope.scopedUsernames(groupAdmin())).containsExactly("carol");
  }

  @Test
  void requireGroupsExistThrowsForMissingGroup() {
    when(groupRepository.existsById("g1")).thenReturn(true);
    when(groupRepository.existsById("g2")).thenReturn(false);

    scope.requireGroupsExist(List.of("g1"));
    assertThatThrownBy(() -> scope.requireGroupsExist(List.of("g1", "g2")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("g2");
  }

  @Test
  void userWithoutRepositoryMatchYieldsNoUsernames() {
    when(assignmentRepository.findById_UserId("gadmin1"))
        .thenReturn(List.of(new GroupAdminAssignment("gadmin1", "g1")));
    when(memberRepository.findById_GroupIdIn(Set.of("g1")))
        .thenReturn(List.of(new GroupMember("g1", "missing")));
    when(userRepository.findAllById(Set.of("missing"))).thenReturn(List.of());

    assertThat(scope.scopedUsernames(groupAdmin())).isEmpty();
  }

  @Test
  void plainUserHasNoScopeRegardlessOfMembership() {
    assertThat(scope.managedRootIds(plainUser())).isEmpty();
    assertThat(scope.scopedGroupIds(plainUser())).isEmpty();
    assertThat(scope.scopedUserIds(plainUser())).isEmpty();
  }
}
