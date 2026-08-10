package com.opnl.vpn.access;

import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pure rule-evaluation logic: resolves a user's effective access rules and renders them into
 * per-client iptables commands. A dedicated chain per client (named from the common name) is
 * created only when the user has rules; otherwise the default FORWARD policy applies.
 */
@Component
public class RuleEngine {

  private final AccessRuleRepository ruleRepository;
  private final GroupMemberRepository memberRepository;
  private final GroupRepository groupRepository;

  public RuleEngine(
      AccessRuleRepository ruleRepository,
      GroupMemberRepository memberRepository,
      GroupRepository groupRepository) {
    this.ruleRepository = ruleRepository;
    this.memberRepository = memberRepository;
    this.groupRepository = groupRepository;
  }

  /** All enabled rules that apply to a user: global, then group (child-first), then user-level. */
  public List<AccessRule> effectiveFor(String userId) {
    List<AccessRule> rules = new ArrayList<>();
    rules.addAll(ruleRepository.findByTargetTypeOrderByPriorityAsc(AccessRule.TargetType.GLOBAL));
    for (String groupId : groupChainFor(userId)) {
      rules.addAll(
          ruleRepository.findByTargetTypeAndTargetIdOrderByPriorityAsc(
              AccessRule.TargetType.GROUP, groupId));
    }
    rules.addAll(
        ruleRepository.findByTargetTypeAndTargetIdOrderByPriorityAsc(
            AccessRule.TargetType.USER, userId));
    rules.removeIf(rule -> !rule.isEnabled());
    rules.sort(Comparator.comparingInt(AccessRule::getPriority));
    return rules;
  }

  public record IptablesResult(List<String> apply, List<String> remove) {}

  /** Builds the iptables argv lists to install and later tear down a client's rules. */
  public IptablesResult iptablesFor(String commonName, String virtualIp, List<AccessRule> rules) {
    String chain = chainName(commonName);
    List<String> apply = new ArrayList<>();
    List<String> remove = new ArrayList<>();

    if (rules.isEmpty()) {
      return new IptablesResult(apply, remove);
    }

    apply.add("iptables -N " + chain);
    // Always permit the connection itself and DNS resolution.
    apply.add("iptables -A " + chain + " -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT");
    apply.add("iptables -A " + chain + " -p udp --dport 53 -j ACCEPT");
    apply.add("iptables -A " + chain + " -p tcp --dport 53 -j ACCEPT");

    String src = virtualIp == null || virtualIp.isBlank() ? "" : "-s " + virtualIp + " ";
    for (AccessRule rule : rules) {
      apply.add("iptables -A " + chain + " " + args(rule, src) + " -j " + target(rule.getAction()));
    }
    // Default deny for clients with any rule.
    apply.add("iptables -A " + chain + " -j DROP");
    apply.add("iptables -I FORWARD " + src + "-j " + chain);

    remove.add("iptables -D FORWARD " + src + "-j " + chain);
    remove.add("iptables -F " + chain);
    remove.add("iptables -X " + chain);
    return new IptablesResult(apply, remove);
  }

  /** Maps a rule action to a real iptables target (ALLOW/DENY are panel-level concepts). */
  private String target(AccessRule.Action action) {
    return switch (action) {
      case ALLOW -> "ACCEPT";
      case DENY -> "DROP";
    };
  }

  /** Computes the stable iptables chain name for a common name. */
  public String chainName(String commonName) {
    byte[] digest;
    try {
      digest =
          MessageDigest.getInstance("SHA-256").digest(commonName.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      digest = commonName.getBytes(StandardCharsets.UTF_8);
    }
    return "OPNL_" + HexFormat.of().formatHex(digest, 0, 6);
  }

  private String args(AccessRule rule, String src) {
    StringBuilder sb = new StringBuilder();
    sb.append(src);
    if (rule.getProtocol() != null) {
      sb.append("-p ").append(rule.getProtocol().name().toLowerCase()).append(" ");
    }
    if (rule.getDstPort() != null) {
      sb.append("--dport ").append(rule.getDstPort()).append(" ");
    }
    // The source is always the client's VPN IP (matched by the per-client chain jump).
    if (rule.getDstCidr() != null && !rule.getDstCidr().isBlank()) {
      sb.append("-d ").append(rule.getDstCidr()).append(" ");
    }
    return sb.toString().trim();
  }

  private List<String> groupChainFor(String userId) {
    List<String> chain = new ArrayList<>();
    List<String> visited = new ArrayList<>();
    for (var member : memberRepository.findById_UserId(userId)) {
      collectAncestors(member.getId().getGroupId(), chain, visited);
    }
    return chain;
  }

  private void collectAncestors(String groupId, List<String> chain, List<String> visited) {
    if (groupId == null || visited.contains(groupId)) {
      return;
    }
    visited.add(groupId);
    chain.add(groupId);
    groupRepository
        .findById(groupId)
        .map(com.opnl.vpn.group.Group::getParentId)
        .ifPresent(parent -> collectAncestors(parent, chain, visited));
  }
}
