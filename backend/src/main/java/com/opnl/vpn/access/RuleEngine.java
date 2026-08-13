package com.opnl.vpn.access;

import com.opnl.vpn.dns.DnsOverrideService;
import com.opnl.vpn.dns.DnsRecord;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure rule-evaluation logic: resolves a user's effective access rules and renders them into
 * per-client iptables commands. A dedicated chain per client (named from the common name) is
 * created only when the user has rules or scope-denied DNS-override addresses; otherwise the
 * default FORWARD policy applies.
 */
@Component
public class RuleEngine {

  private final AccessRuleRepository ruleRepository;
  private final GroupMemberRepository memberRepository;
  private final GroupRepository groupRepository;
  private final SettingsService settingsService;
  private final UserRepository userRepository;
  private final DomainResolver domainResolver;
  private final DnsOverrideService dnsOverrideService;

  public RuleEngine(
      AccessRuleRepository ruleRepository,
      GroupMemberRepository memberRepository,
      GroupRepository groupRepository,
      SettingsService settingsService,
      UserRepository userRepository,
      DomainResolver domainResolver,
      DnsOverrideService dnsOverrideService) {
    this.ruleRepository = ruleRepository;
    this.memberRepository = memberRepository;
    this.groupRepository = groupRepository;
    this.settingsService = settingsService;
    this.userRepository = userRepository;
    this.domainResolver = domainResolver;
    this.dnsOverrideService = dnsOverrideService;
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
    return iptablesFor(commonName, virtualIp, rules, Set.of());
  }

  /**
   * Builds the iptables argv lists to install and later tear down a client's rules, additionally
   * dropping traffic to the given DNS-override addresses (scope denies). The terminal rule is DROP
   * when real access rules exist (default deny) and ACCEPT when only scope denies are present, so
   * otherwise-unrestricted clients are not locked down beyond their deny list.
   */
  public IptablesResult iptablesFor(
      String commonName, String virtualIp, List<AccessRule> rules, Set<String> deniedIps) {
    String chain = chainName(commonName);
    List<String> apply = new ArrayList<>();
    List<String> remove = new ArrayList<>();

    boolean hasRules = !rules.isEmpty();
    boolean hasDenies = deniedIps != null && !deniedIps.isEmpty();
    if (!hasRules && !hasDenies) {
      return new IptablesResult(apply, remove);
    }

    apply.add("iptables -N " + chain);
    // Always permit the connection itself and DNS resolution.
    apply.add("iptables -A " + chain + " -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT");
    apply.add("iptables -A " + chain + " -p udp --dport 53 -j ACCEPT");
    apply.add("iptables -A " + chain + " -p tcp --dport 53 -j ACCEPT");

    String src = virtualIp == null || virtualIp.isBlank() ? "" : "-s " + virtualIp + " ";
    for (AccessRule rule : rules) {
      for (String dst : destinationSpecs(rule)) {
        apply.add(
            "iptables -A "
                + chain
                + " "
                + args(rule, src, dst)
                + " -j "
                + target(rule.getAction()));
      }
    }
    for (String ip : deniedIps) {
      apply.add("iptables -A " + chain + " " + src + "-d " + ip + "/32 -j DROP");
    }
    // Default deny for clients with any access rule; scope-only chains stay open.
    apply.add("iptables -A " + chain + " -j " + (hasRules ? "DROP" : "ACCEPT"));
    apply.add("iptables -I FORWARD " + src + "-j " + chain);

    remove.add("iptables -D FORWARD " + src + "-j " + chain);
    remove.add("iptables -F " + chain);
    remove.add("iptables -X " + chain);
    return new IptablesResult(apply, remove);
  }

  /**
   * The IPv4 addresses of enabled non-GLOBAL DNS overrides the user may not reach: records whose
   * scope target (exact user, or group chain membership) does not include the user. GLOBAL records
   * never deny. Used by the connect path so scoped internal hostnames resolve for everyone but only
   * authorized clients can actually reach them.
   */
  public Set<String> scopeDenyIpsFor(String userId) {
    Set<String> denied = new LinkedHashSet<>();
    for (DnsRecord record : dnsOverrideService.nonGlobalEnabled()) {
      if (!scopeIncludes(record, userId)) {
        denied.add(record.getIpv4());
      }
    }
    return denied;
  }

  private boolean scopeIncludes(DnsRecord record, String userId) {
    return switch (record.getScope()) {
      case USER -> userId.equals(record.getScopeId());
      case GROUP -> groupChainFor(userId).contains(record.getScopeId());
      case GLOBAL -> true;
    };
  }

  /**
   * Resolves a rule's destination to one or more iptables match fragments. A {@code dstGroupId}
   * rule targets the group's allocated subnet: the group's static IP pool range as an exact {@code
   * --dst-range}, or the members' static IPs as individual /32 matches when no pool exists. A
   * {@code dstDomain} rule targets the domain's current IPv4 addresses (one /32 match each). An
   * empty list means the destination cannot be resolved and the rule is skipped.
   */
  List<String> destinationSpecs(AccessRule rule) {
    if (rule.getDstGroupId() != null && !rule.getDstGroupId().isBlank()) {
      return groupDestinationSpecs(rule.getDstGroupId());
    }
    if (rule.getDstDomain() != null && !rule.getDstDomain().isBlank()) {
      return domainDestinationSpecs(rule.getDstDomain());
    }
    if (rule.getDstCidr() != null && !rule.getDstCidr().isBlank()) {
      return List.of("-d " + rule.getDstCidr() + " ");
    }
    return List.of("");
  }

  private List<String> domainDestinationSpecs(String domain) {
    // DNS overrides win: an internal hostname resolves to the authoritative override
    // address, keeping the dnsmasq answer and the firewall rules in agreement.
    Set<String> overrideIps = dnsOverrideService.resolveDomain(domain);
    if (!overrideIps.isEmpty()) {
      return overrideIps.stream().map(ip -> "-d " + ip + "/32 ").toList();
    }
    return domainResolver.resolve(domain).stream().map(ip -> "-d " + ip + "/32 ").toList();
  }

  private List<String> groupDestinationSpecs(String groupId) {
    String pool = poolForGroupChain(groupId);
    if (pool != null) {
      return List.of("-m iprange --dst-range " + normalizeRange(pool) + " ");
    }
    List<String> specs = new ArrayList<>();
    for (GroupMember member : memberRepository.findById_GroupId(groupId)) {
      userRepository
          .findById(member.getId().getUserId())
          .map(User::getStaticIp)
          .filter(ip -> ip != null && !ip.isBlank())
          .ifPresent(ip -> specs.add("-d " + ip + "/32 "));
    }
    return specs;
  }

  /** The nearest static IP pool defined on the group or any of its ancestors, or null. */
  private String poolForGroupChain(String groupId) {
    String current = groupId;
    while (current != null && !current.isBlank()) {
      Object pool = settingsService.groupSettings(current).get(SettingKeys.STATIC_IP_POOL);
      if (pool != null && !pool.toString().isBlank()) {
        return pool.toString();
      }
      current = groupRepository.findById(current).map(Group::getParentId).orElse(null);
    }
    return null;
  }

  /** Normalizes a "start-end" pool expression into iptables iprange syntax. */
  private String normalizeRange(String pool) {
    String[] parts = pool.trim().split("-");
    if (parts.length == 2) {
      return parts[0].trim() + "-" + parts[1].trim();
    }
    return pool.trim();
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

  private String args(AccessRule rule, String src, String dst) {
    StringBuilder sb = new StringBuilder();
    sb.append(src);
    if (rule.getProtocol() != null) {
      sb.append("-p ").append(rule.getProtocol().name().toLowerCase()).append(" ");
    }
    if (rule.getDstPort() != null) {
      sb.append("--dport ").append(rule.getDstPort()).append(" ");
    }
    // The source is always the client's VPN IP (matched by the per-client chain jump).
    sb.append(dst);
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
