package com.passagevpn.group;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link GroupAdminAssignment} links. */
public interface GroupAdminAssignmentRepository
    extends JpaRepository<GroupAdminAssignment, GroupAdminAssignment.Id> {
  List<GroupAdminAssignment> findById_GroupId(String groupId);

  List<GroupAdminAssignment> findById_UserId(String userId);

  List<GroupAdminAssignment> findById_UserIdIn(Collection<String> userIds);

  void deleteById_GroupId(String groupId);
}
