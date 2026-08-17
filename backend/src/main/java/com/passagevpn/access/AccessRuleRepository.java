package com.passagevpn.access;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access rule repository. */
public interface AccessRuleRepository extends JpaRepository<AccessRule, String> {

  List<AccessRule> findByTargetTypeOrderByPriorityAsc(AccessRule.TargetType targetType);

  List<AccessRule> findByTargetTypeAndTargetIdOrderByPriorityAsc(
      AccessRule.TargetType targetType, String targetId);

  List<AccessRule> findByEnabledTrueAndDstDomainIsNotNull();
}
