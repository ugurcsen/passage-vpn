package com.passagevpn.access;

import com.passagevpn.group.Group;
import com.passagevpn.group.GroupMemberRepository;
import com.passagevpn.group.GroupRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a user's effective group ancestry chain (direct memberships then ancestors,
 * child-first). Shared by the rule engine and the DNS-override conflict detection so both systems
 * agree on group membership semantics.
 */
public final class GroupHierarchy {

  private GroupHierarchy() {}

  /** The effective group ids for {@code userId}, child-first, deduplicated. */
  public static List<String> chainFor(
      String userId, GroupMemberRepository memberRepository, GroupRepository groupRepository) {
    List<String> chain = new ArrayList<>();
    List<String> visited = new ArrayList<>();
    for (var member : memberRepository.findById_UserId(userId)) {
      collectAncestors(member.getId().getGroupId(), chain, visited, groupRepository);
    }
    return chain;
  }

  private static void collectAncestors(
      String groupId, List<String> chain, List<String> visited, GroupRepository groupRepository) {
    if (groupId == null || visited.contains(groupId)) {
      return;
    }
    visited.add(groupId);
    chain.add(groupId);
    groupRepository
        .findById(groupId)
        .map(Group::getParentId)
        .ifPresent(parent -> collectAncestors(parent, chain, visited, groupRepository));
  }
}
