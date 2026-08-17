package com.passagevpn.group;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link Group} entities. */
public interface GroupRepository extends JpaRepository<Group, String> {
  Optional<Group> findByName(String name);

  boolean existsByName(String name);

  List<Group> findByParentId(String parentId);
}
