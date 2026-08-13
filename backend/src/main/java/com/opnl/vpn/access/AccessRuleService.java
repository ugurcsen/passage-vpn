package com.opnl.vpn.access;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.dns.DnsScopeConflictService;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.network.DnsmasqConfigService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  private final AuditLogService auditLogService;
  private final DnsmasqConfigService dnsmasqConfigService;
  private final DnsScopeConflictService conflictService;

  public AccessRuleService(
      AccessRuleRepository ruleRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      RuleEngine ruleEngine,
      AuditLogService auditLogService,
      DnsmasqConfigService dnsmasqConfigService,
      DnsScopeConflictService conflictService) {
    this.ruleRepository = ruleRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.ruleEngine = ruleEngine;
    this.auditLogService = auditLogService;
    this.dnsmasqConfigService = dnsmasqConfigService;
    this.conflictService = conflictService;
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
                        rule.getTargetType().name().toLowerCase() + ":" + rule.getTargetId()),
                    dstGroupName(rule.getDstGroupId()),
                    conflictService.warningsForRule(rule)))
        .toList();
  }

  @Transactional
  public AccessRuleDto create(AccessRuleDto dto) {
    validateTarget(dto.targetType(), dto.targetId());
    validateDestination(dto.dstGroupId());
    AccessRule rule = new AccessRule();
    rule.setId(UUID.randomUUID().toString());
    rule.setCreatedAt(java.time.Instant.now());
    apply(rule, dto);
    rule.setPriority(nextPriority());
    AccessRule saved = ruleRepository.save(rule);
    auditLogService.record(
        "RULE_CREATE",
        AuditLogService.CAT_RULE,
        saved.getId(),
        "rule",
        Map.of(
            "target",
            dto.targetType().name().toLowerCase() + ":" + dto.targetId(),
            "action",
            dto.action().name(),
            "dst",
            destinationLabel(dto)));
    refreshDnsmasq();
    return AccessRuleDto.from(
        saved,
        targetName(dto.targetType(), dto.targetId()),
        dstGroupName(dto.dstGroupId()),
        conflictService.warningsForRule(saved));
  }

  @Transactional
  public AccessRuleDto update(String id, AccessRuleDto dto) {
    AccessRule rule = requireRule(id);
    validateTarget(dto.targetType(), dto.targetId());
    validateDestination(dto.dstGroupId());
    apply(rule, dto);
    AccessRule saved = ruleRepository.save(rule);
    auditLogService.record("RULE_UPDATE", AuditLogService.CAT_RULE, id, "rule", null);
    refreshDnsmasq();
    return AccessRuleDto.from(
        saved,
        targetName(dto.targetType(), dto.targetId()),
        dstGroupName(dto.dstGroupId()),
        conflictService.warningsForRule(saved));
  }

  @Transactional
  public void delete(String id) {
    requireRule(id);
    ruleRepository.deleteById(id);
    auditLogService.record("RULE_DELETE", AuditLogService.CAT_RULE, id, "rule", null);
    refreshDnsmasq();
  }

  @Transactional
  public AccessRuleDto setEnabled(String id, boolean enabled) {
    AccessRule rule = requireRule(id);
    rule.setEnabled(enabled);
    auditLogService.record(
        enabled ? "RULE_ENABLE" : "RULE_DISABLE", AuditLogService.CAT_RULE, id, "rule", null);
    refreshDnsmasq();
    return AccessRuleDto.from(
        ruleRepository.save(rule),
        targetName(rule.getTargetType(), rule.getTargetId()),
        dstGroupName(rule.getDstGroupId()),
        conflictService.warningsForRule(rule));
  }

  /**
   * Renders the per-client iptables and, when the serving daemon is dual-stack, ip6tables commands
   * for an active connection. {@code virtualIp6} is the client's tunnel IPv6 address (blank for
   * IPv4-only clients); it scopes the IPv6 chain to the client and is skipped when absent.
   */
  @Transactional(readOnly = true)
  public RuleEngine.IptablesResult iptablesFor(
      String commonName, String virtualIp, String virtualIp6, String userId, boolean ipv6Enabled) {
    return ruleEngine.iptablesFor(
        commonName,
        virtualIp,
        virtualIp6,
        ruleEngine.effectiveFor(userId),
        ruleEngine.scopeDenyIpsFor(userId),
        ruleEngine.scopeDenyIpv6For(userId),
        ipv6Enabled);
  }

  private void apply(AccessRule rule, AccessRuleDto dto) {
    rule.setTargetType(dto.targetType());
    rule.setTargetId(dto.targetId());
    rule.setAction(dto.action());
    rule.setProtocol(dto.protocol());
    rule.setDstCidr(dto.dstCidr());
    rule.setDstGroupId(dto.dstGroupId());
    rule.setDstDomain(dto.dstDomain());
    rule.setDstPort(dto.dstPort());
    rule.setEnabled(dto.enabled() == null || dto.enabled());
  }

  private void refreshDnsmasq() {
    try {
      dnsmasqConfigService.refresh();
    } catch (RuntimeException e) {
      // A dnsmasq render/write failure must never break rule CRUD.
    }
  }

  /** Human-readable destination for audit detail: "cidr:10.0.0.0/8", "group:<name>", "domain:x". */
  private String destinationLabel(AccessRuleDto dto) {
    if (dto.dstDomain() != null && !dto.dstDomain().isBlank()) {
      return "domain:" + dto.dstDomain();
    }
    if (dto.dstGroupId() != null && !dto.dstGroupId().isBlank()) {
      String name = dstGroupName(dto.dstGroupId());
      return "group:" + (name == null ? dto.dstGroupId() : name);
    }
    if (dto.dstCidr() != null && !dto.dstCidr().isBlank()) {
      return "cidr:" + dto.dstCidr();
    }
    return "any";
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

  private void validateDestination(String dstGroupId) {
    if (dstGroupId == null || dstGroupId.isBlank()) {
      return;
    }
    if (groupRepository.findById(dstGroupId).isEmpty()) {
      throw ApiException.notFound("dst_group_not_found", "Destination group not found");
    }
  }

  private String dstGroupName(String dstGroupId) {
    if (dstGroupId == null || dstGroupId.isBlank()) {
      return null;
    }
    return groupRepository.findById(dstGroupId).map(Group::getName).orElse(null);
  }

  private String targetName(AccessRule.TargetType type, String targetId) {
    if (type == AccessRule.TargetType.GLOBAL) {
      return null;
    }
    return type == AccessRule.TargetType.USER
        ? userRepository.findById(targetId).map(User::getUsername).orElse(null)
        : groupRepository.findById(targetId).map(Group::getName).orElse(null);
  }
}
