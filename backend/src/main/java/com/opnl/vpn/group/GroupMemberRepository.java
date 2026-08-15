package com.opnl.vpn.group;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link GroupMember} links. */
public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMember.Id> {
  List<GroupMember> findById_GroupId(String groupId);

  List<GroupMember> findById_UserId(String userId);

  List<GroupMember> findById_UserIdIn(Collection<String> userIds);

  List<GroupMember> findById_GroupIdIn(Collection<String> groupIds);

  void deleteById_GroupId(String groupId);
}
