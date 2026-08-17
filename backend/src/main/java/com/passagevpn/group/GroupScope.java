package com.passagevpn.group;

import com.passagevpn.common.ApiException;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Resolves what a GROUP_ADMIN may reach: the root groups they are assigned to plus every subgroup
 * in those subtrees. A GROUP_ADMIN's scope covers the users who are members of any group in the
 * scope (their VPN memberships, not the admin's own account), the groups themselves, and — via
 * {@link #scopedUsernames} — the connection history of those users. ADMINS are unrestricted.
 */
@Service
public class GroupScope {

  private final GroupRepository groupRepository;
  private final GroupAdminAssignmentRepository assignmentRepository;
  private final GroupMemberRepository memberRepository;
  private final UserRepository userRepository;

  public GroupScope(
      GroupRepository groupRepository,
      GroupAdminAssignmentRepository assignmentRepository,
      GroupMemberRepository memberRepository,
      UserRepository userRepository) {
    this.groupRepository = groupRepository;
    this.assignmentRepository = assignmentRepository;
    this.memberRepository = memberRepository;
    this.userRepository = userRepository;
  }

  /** True for the unrestricted ADMIN role. */
  public boolean isAdmin(User actor) {
    return actor.getRole() == User.Role.ADMIN;
  }

  /** True when this actor has any manage rights at all (ADMIN, or GROUP_ADMIN with assignments). */
  public boolean canManage(User actor) {
    return isAdmin(actor)
        || (actor.getRole() == User.Role.GROUP_ADMIN && !managedRootIds(actor).isEmpty());
  }

  /**
   * Root group ids assigned to a GROUP_ADMIN. {@code null} for ADMIN (meaning "all"), empty for
   * non-managing roles.
   */
  public Set<String> managedRootIds(User actor) {
    if (isAdmin(actor)) {
      return null;
    }
    if (actor.getRole() != User.Role.GROUP_ADMIN) {
      return Set.of();
    }
    return assignmentRepository.findById_UserId(actor.getId()).stream()
        .map(a -> a.getId().getGroupId())
        .collect(Collectors.toSet());
  }

  /** All group ids this actor may manage: the union of their assigned subtrees (all for ADMIN). */
  public Set<String> scopedGroupIds(User actor) {
    Set<String> roots = managedRootIds(actor);
    if (roots == null) {
      return groupRepository.findAll().stream().map(Group::getId).collect(Collectors.toSet());
    }
    Set<String> result = new HashSet<>();
    for (String root : roots) {
      result.add(root);
      result.addAll(descendantIds(root));
    }
    return result;
  }

  /** Whether this actor may manage the given group (read or write). */
  public boolean managesGroup(User actor, String groupId) {
    return isAdmin(actor) || scopedGroupIds(actor).contains(groupId);
  }

  /** Whether this actor may create a subgroup under {@code parentId} (or a new root for ADMIN). */
  public boolean canCreateSubgroup(User actor, String parentId) {
    if (isAdmin(actor)) {
      return true;
    }
    return parentId != null && managesGroup(actor, parentId);
  }

  /**
   * Whether this actor may manage the given target account: ADMINS manage everyone; GROUP_ADMINS
   * manage accounts (only non-admin ones) that belong to a group inside their scope.
   */
  public boolean managesUser(User actor, String targetUserId) {
    if (isAdmin(actor)) {
      return true;
    }
    Set<String> scoped = scopedGroupIds(actor);
    if (scoped.isEmpty()) {
      return false;
    }
    return memberRepository.findById_UserId(targetUserId).stream()
        .anyMatch(m -> scoped.contains(m.getId().getGroupId()));
  }

  /** User ids inside this actor's scope ({@code null} for ADMIN meaning all users). */
  public Set<String> scopedUserIds(User actor) {
    if (isAdmin(actor)) {
      return null;
    }
    Set<String> scoped = scopedGroupIds(actor);
    if (scoped.isEmpty()) {
      return Set.of();
    }
    return memberRepository.findById_GroupIdIn(scoped).stream()
        .map(m -> m.getId().getUserId())
        .collect(Collectors.toSet());
  }

  /** Usernames inside this actor's scope ({@code null} for ADMIN meaning all users). */
  public Set<String> scopedUsernames(User actor) {
    Set<String> ids = scopedUserIds(actor);
    if (ids == null) {
      return null;
    }
    if (ids.isEmpty()) {
      return Set.of();
    }
    return userRepository.findAllById(ids).stream()
        .map(User::getUsername)
        .collect(Collectors.toSet());
  }

  /** Validates that every group id exists. Used when persisting adminGroupIds. */
  public void requireGroupsExist(Collection<String> groupIds) {
    for (String groupId : groupIds) {
      if (!groupRepository.existsById(groupId)) {
        throw ApiException.notFound("group_not_found", "Group not found: " + groupId);
      }
    }
  }

  /** All descendant group ids of {@code rootId}, excluding the root itself. */
  private List<String> descendantIds(String rootId) {
    List<String> descendants = new ArrayList<>();
    Deque<String> queue =
        new ArrayDeque<>(
            groupRepository.findByParentId(rootId).stream().map(Group::getId).toList());
    while (!queue.isEmpty()) {
      String current = queue.poll();
      descendants.add(current);
      queue.addAll(groupRepository.findByParentId(current).stream().map(Group::getId).toList());
    }
    return descendants;
  }
}
