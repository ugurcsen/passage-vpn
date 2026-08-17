package com.passagevpn.dns;

import com.passagevpn.access.AccessRule;
import com.passagevpn.access.AccessRuleRepository;
import com.passagevpn.access.CidrUtil;
import com.passagevpn.access.GroupHierarchy;
import com.passagevpn.group.Group;
import com.passagevpn.group.GroupMemberRepository;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Detects when a scoped DNS override's access boundary can be silently defeated by an explicit
 * ALLOW access rule. The per-client firewall emits scope-deny DROP rules AFTER access-rule ACCEPTs
 * (explicit ALLOW wins), so an ALLOW rule whose destination covers a scoped override address lets
 * out-of-scope users reach it despite the scope. This service surfaces those cases as admin-facing
 * warnings on the DNS Overrides and Access Rules pages.
 */
@Service
public class DnsScopeConflictService {

  private final AccessRuleRepository ruleRepository;
  private final GroupMemberRepository memberRepository;
  private final GroupRepository groupRepository;
  private final UserRepository userRepository;
  private final DnsRecordRepository recordRepository;

  public DnsScopeConflictService(
      AccessRuleRepository ruleRepository,
      GroupMemberRepository memberRepository,
      GroupRepository groupRepository,
      UserRepository userRepository,
      DnsRecordRepository recordRepository) {
    this.ruleRepository = ruleRepository;
    this.memberRepository = memberRepository;
    this.groupRepository = groupRepository;
    this.userRepository = userRepository;
    this.recordRepository = recordRepository;
  }

  /**
   * Warnings for a single DNS record: human-readable descriptions of every enabled ALLOW rule that
   * defeats the record's scope deny. Empty for GLOBAL records (never denied) and when no rule
   * covers the address or the rule's audience stays inside the override scope.
   */
  public List<String> warningsForRecord(DnsRecord record) {
    if (record.getScope() == DnsRecord.Scope.GLOBAL) {
      return List.of();
    }
    List<String> warnings = new ArrayList<>();
    for (AccessRule rule : ruleRepository.findAll()) {
      if (!isAllow(rule) || !covers(rule, record) || !audienceIncludesOutOfScope(rule, record)) {
        continue;
      }
      warnings.add(
          "ALLOW rule '"
              + describeRule(rule)
              + "' applies to users outside this scope, so they can reach "
              + record.getIpv4()
              + " despite the restriction");
    }
    return warnings;
  }

  /**
   * Warnings for a single access rule: whether an enabled ALLOW rule (destination CIDR or domain)
   * defeats the scope deny of any enabled non-GLOBAL DNS override. Empty when the rule is a DENY,
   * targets a group subnet, or only covers addresses whose scopes include every applicable user.
   */
  public List<String> warningsForRule(AccessRule rule) {
    if (!isAllow(rule) || (isBlank(rule.getDstCidr()) && isBlank(rule.getDstDomain()))) {
      return List.of();
    }
    List<String> warnings = new ArrayList<>();
    for (DnsRecord record : recordRepository.findByEnabledTrue()) {
      if (record.getScope() == DnsRecord.Scope.GLOBAL) {
        continue;
      }
      if (!covers(rule, record) || !audienceIncludesOutOfScope(rule, record)) {
        continue;
      }
      warnings.add(
          "Scoped DNS override '"
              + record.getHostname()
              + "' ("
              + record.getIpv4()
              + ", "
              + scopeLabel(record)
              + ") becomes reachable by out-of-scope users through this rule");
    }
    return warnings;
  }

  private boolean isAllow(AccessRule rule) {
    return rule.isEnabled() && rule.getAction() == AccessRule.Action.ALLOW;
  }

  private boolean covers(AccessRule rule, DnsRecord record) {
    if (!isBlank(rule.getDstCidr())) {
      return CidrUtil.contains(rule.getDstCidr(), record.getIpv4())
          || (record.getIpv6() != null && CidrUtil.contains(rule.getDstCidr(), record.getIpv6()));
    }
    if (!isBlank(rule.getDstDomain())) {
      return rule.getDstDomain().equalsIgnoreCase(record.getHostname());
    }
    return false;
  }

  /**
   * Whether the rule's audience (GLOBAL, or the target group/user) includes a user outside the
   * scope.
   */
  private boolean audienceIncludesOutOfScope(AccessRule rule, DnsRecord record) {
    return switch (rule.getTargetType()) {
      case GLOBAL -> true;
      case USER -> userOutOfScope(rule.getTargetId(), record);
      case GROUP -> groupHasOutOfScopeMember(rule.getTargetId(), record);
    };
  }

  private boolean userOutOfScope(String userId, DnsRecord record) {
    return switch (record.getScope()) {
      case GLOBAL -> false;
      case USER -> !record.getScopeId().equals(userId);
      case GROUP -> !chainFor(userId).contains(record.getScopeId());
    };
  }

  private boolean groupHasOutOfScopeMember(String groupId, DnsRecord record) {
    for (var member : memberRepository.findById_GroupId(groupId)) {
      if (userOutOfScope(member.getId().getUserId(), record)) {
        return true;
      }
    }
    return false;
  }

  private List<String> chainFor(String userId) {
    return GroupHierarchy.chainFor(userId, memberRepository, groupRepository);
  }

  private String describeRule(AccessRule rule) {
    String target =
        switch (rule.getTargetType()) {
          case GLOBAL -> "GLOBAL";
          case USER -> "User: " + userName(rule.getTargetId());
          case GROUP -> "Group: " + groupName(rule.getTargetId());
        };
    String dest = !isBlank(rule.getDstCidr()) ? rule.getDstCidr() : "domain:" + rule.getDstDomain();
    return target + " " + dest;
  }

  private String scopeLabel(DnsRecord record) {
    return record.getScope() == DnsRecord.Scope.USER
        ? "User: " + userName(record.getScopeId())
        : "Group: " + groupName(record.getScopeId());
  }

  private String userName(String userId) {
    return userRepository.findById(userId).map(User::getUsername).orElse(userId);
  }

  private String groupName(String groupId) {
    return groupRepository.findById(groupId).map(Group::getName).orElse(groupId);
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
