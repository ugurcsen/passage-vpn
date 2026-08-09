package com.opnl.vpn.access;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD for access rules plus resolution of a user's effective rule set. */
@Service
public class AccessRuleService {

  private final AccessRuleRepository ruleRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final RuleEngine ruleEngine;

  public AccessRuleService(
      AccessRuleRepository ruleRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      RuleEngine ruleEngine) {
    this.ruleRepository = ruleRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.ruleEngine = ruleEngine;
  }

  @Transactional(readOnly = true)
  public List<AccessRuleDto> list() {
    Map<String, String> targets =
        userRepository.findAll().stream()
            .collect(Collectors.toMap(u -> "user:" + u.getId(), u -> u.getUsername(), (a, b) -> a));
    groupRepository.findAll().forEach(g -> targets.put("group:" + g.getId(), g.getName()));
    return ruleRepository.findAll().stream()
        .sorted(java.util.Comparator.comparingInt(AccessRule::getPriority))
        .map(
            rule ->
                AccessRuleDto.from(
                    rule,
                    targets.get(
                        rule.getTargetType().name().toLowerCase() + ":" + rule.getTargetId())))
        .toList();
  }

  @Transactional
  public AccessRuleDto create(AccessRuleDto dto) {
    validateTarget(dto.targetType(), dto.targetId());
    AccessRule rule = new AccessRule();
    apply(rule, dto);
    rule.setPriority(nextPriority());
    return AccessRuleDto.from(ruleRepository.save(rule), dto.targetName());
  }

  @Transactional
  public AccessRuleDto update(String id, AccessRuleDto dto) {
    AccessRule rule = requireRule(id);
    validateTarget(dto.targetType(), dto.targetId());
    apply(rule, dto);
    return AccessRuleDto.from(ruleRepository.save(rule), dto.targetName());
  }

  @Transactional
  public void delete(String id) {
    requireRule(id);
    ruleRepository.deleteById(id);
  }

  @Transactional
  public AccessRuleDto setEnabled(String id, boolean enabled) {
    AccessRule rule = requireRule(id);
    rule.setEnabled(enabled);
    return AccessRuleDto.from(ruleRepository.save(rule), null);
  }

  /** Renders the per-client iptables commands for an active connection. */
  @Transactional(readOnly = true)
  public RuleEngine.IptablesResult iptablesFor(String commonName, String virtualIp, String userId) {
    return ruleEngine.iptablesFor(commonName, virtualIp, ruleEngine.effectiveFor(userId));
  }

  private void apply(AccessRule rule, AccessRuleDto dto) {
    rule.setTargetType(dto.targetType());
    rule.setTargetId(dto.targetId());
    rule.setAction(dto.action());
    rule.setProtocol(dto.protocol());
    rule.setDstCidr(dto.dstCidr());
    rule.setDstPort(dto.dstPort());
    rule.setEnabled(dto.enabled() == null || dto.enabled());
  }

  private int nextPriority() {
    return ruleRepository.findAll().stream().mapToInt(AccessRule::getPriority).max().orElse(-1) + 1;
  }

  private AccessRule requireRule(String id) {
    return ruleRepository
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("rule_not_found", "Access rule not found"));
  }

  private void validateTarget(AccessRule.TargetType type, String targetId) {
    if (type == AccessRule.TargetType.GLOBAL) {
      return;
    }
    if (targetId == null || targetId.isBlank()) {
      throw ApiException.badRequest("missing_target", "Target id is required");
    }
    if (type == AccessRule.TargetType.USER && userRepository.findById(targetId).isEmpty()) {
      throw ApiException.notFound("user_not_found", "Target user not found");
    }
    if (type == AccessRule.TargetType.GROUP && groupRepository.findById(targetId).isEmpty()) {
      throw ApiException.notFound("group_not_found", "Target group not found");
    }
  }
}
