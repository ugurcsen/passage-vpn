package com.passagevpn.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.access.AccessRule.Action;
import com.passagevpn.access.AccessRule.TargetType;
import com.passagevpn.dns.DnsOverrideService;
import com.passagevpn.dns.DnsRecord;
import com.passagevpn.group.Group;
import com.passagevpn.group.GroupMember;
import com.passagevpn.group.GroupMemberRepository;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

  private AccessRule rule(
      TargetType type, String targetId, Action action, int priority, boolean enabled) {
    return AccessRule.builder()
        .id("r" + priority)
        .targetType(type)
        .targetId(targetId)
        .action(action)
        .priority(priority)
        .enabled(enabled)
        .createdAt(Instant.now())
        .build();
  }

  private RuleEngine engine(
      AccessRuleRepository repo, GroupMemberRepository members, GroupRepository groups) {
    return new RuleEngine(
        repo,
        members,
        groups,
        mock(SettingsService.class),
        mock(UserRepository.class),
        mock(DomainResolver.class),
        mock(DnsOverrideService.class));
  }

  private RuleEngine engineWithResolver(DomainResolver resolver) {
    return new RuleEngine(
        mock(AccessRuleRepository.class),
        mock(GroupMemberRepository.class),
        mock(GroupRepository.class),
        mock(SettingsService.class),
        mock(UserRepository.class),
        resolver,
        mock(DnsOverrideService.class));
  }

  @Test
  void effectiveRulesCombineGlobalGroupAndUserRules() {
    AccessRuleRepository repo = mock(AccessRuleRepository.class);
    when(repo.findByTargetTypeOrderByPriorityAsc(TargetType.GLOBAL))
        .thenReturn(List.of(rule(TargetType.GLOBAL, null, Action.DENY, 0, true)));
    when(repo.findByTargetTypeAndTargetIdOrderByPriorityAsc(TargetType.GROUP, "g1"))
        .thenReturn(List.of(rule(TargetType.GROUP, "g1", Action.ALLOW, 5, true)));
    when(repo.findByTargetTypeAndTargetIdOrderByPriorityAsc(TargetType.USER, "u1"))
        .thenReturn(List.of(rule(TargetType.USER, "u1", Action.DENY, 2, true)));

    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g1", "u1")));
    GroupRepository groups = mock(GroupRepository.class);
    when(groups.findById("g1"))
        .thenReturn(Optional.of(Group.builder().id("g1").name("g1").build()));

    RuleEngine engine = engine(repo, members, groups);
    List<AccessRule> rules = engine.effectiveFor("u1");

    assertThat(rules)
        .extracting(AccessRule::getAction)
        .containsExactly(Action.DENY, Action.DENY, Action.ALLOW);
  }

  @Test
  void disabledRulesAreSkipped() {
    AccessRuleRepository repo = mock(AccessRuleRepository.class);
    when(repo.findByTargetTypeOrderByPriorityAsc(TargetType.GLOBAL))
        .thenReturn(List.of(rule(TargetType.GLOBAL, null, Action.DENY, 0, false)));

    RuleEngine engine =
        engine(repo, mock(GroupMemberRepository.class), mock(GroupRepository.class));
    assertThat(engine.effectiveFor("u1")).isEmpty();
  }

  @Test
  void noRulesMeansNoIptablesCommands() {
    AccessRuleRepository repo = mock(AccessRuleRepository.class);
    when(repo.findByTargetTypeOrderByPriorityAsc(TargetType.GLOBAL)).thenReturn(List.of());

    RuleEngine engine =
        engine(repo, mock(GroupMemberRepository.class), mock(GroupRepository.class));
    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of());

    assertThat(result.apply()).isEmpty();
    assertThat(result.remove()).isEmpty();
  }

  @Test
  void iptablesIncludesChainDefaultsAndPerClientJump() {
    AccessRule allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    allow.setProtocol(AccessRule.Protocol.TCP);
    allow.setDstPort(443);
    allow.setDstCidr("10.0.0.0/8");

    RuleEngine engine =
        engine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            mock(GroupRepository.class));
    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(allow));

    String chain = engine.chainName("alice");
    assertThat(chain).startsWith("PASSAGE_").hasSizeGreaterThan(4);
    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("iptables -N " + chain))
        .anyMatch(cmd -> cmd.contains("--dport 443"))
        .anyMatch(cmd -> cmd.contains("-d 10.0.0.0/8"))
        .anyMatch(cmd -> cmd.contains("-j ACCEPT"))
        .anyMatch(cmd -> cmd.contains("-j DROP"))
        .anyMatch(cmd -> cmd.contains("-I FORWARD -s 10.8.0.5 -j " + chain));
    assertThat(result.remove())
        .anyMatch(cmd -> cmd.contains("-D FORWARD -s 10.8.0.5 -j " + chain))
        .anyMatch(cmd -> cmd.contains("-X " + chain));
  }

  @Test
  void nestedGroupRulesAreCollectedChildFirst() {
    AccessRuleRepository repo = mock(AccessRuleRepository.class);
    when(repo.findByTargetTypeOrderByPriorityAsc(TargetType.GLOBAL)).thenReturn(List.of());
    when(repo.findByTargetTypeAndTargetIdOrderByPriorityAsc(TargetType.GROUP, "g2"))
        .thenReturn(List.of(rule(TargetType.GROUP, "g2", Action.ALLOW, 1, true)));
    when(repo.findByTargetTypeAndTargetIdOrderByPriorityAsc(TargetType.GROUP, "g1"))
        .thenReturn(List.of(rule(TargetType.GROUP, "g1", Action.DENY, 2, true)));
    when(repo.findByTargetTypeAndTargetIdOrderByPriorityAsc(TargetType.GROUP, "g0"))
        .thenReturn(List.of(rule(TargetType.GROUP, "g0", Action.ALLOW, 3, true)));

    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g2", "u1")));
    GroupRepository groups = mock(GroupRepository.class);
    when(groups.findById("g2"))
        .thenReturn(Optional.of(Group.builder().id("g2").name("g2").parentId("g1").build()));
    when(groups.findById("g1"))
        .thenReturn(Optional.of(Group.builder().id("g1").name("g1").parentId("g0").build()));
    when(groups.findById("g0"))
        .thenReturn(Optional.of(Group.builder().id("g0").name("g0").build()));

    RuleEngine engine = engine(repo, members, groups);
    List<AccessRule> rules = engine.effectiveFor("u1");

    assertThat(rules).extracting(AccessRule::getTargetId).containsExactly("g2", "g1", "g0");
  }

  @Test
  void groupAncestryCycleIsTerminated() {
    AccessRuleRepository repo = mock(AccessRuleRepository.class);
    when(repo.findByTargetTypeOrderByPriorityAsc(TargetType.GLOBAL)).thenReturn(List.of());
    when(repo.findByTargetTypeAndTargetIdOrderByPriorityAsc(TargetType.GROUP, "g1"))
        .thenReturn(List.of());
    when(repo.findByTargetTypeAndTargetIdOrderByPriorityAsc(TargetType.GROUP, "g2"))
        .thenReturn(List.of());

    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g1", "u1")));
    GroupRepository groups = mock(GroupRepository.class);
    when(groups.findById("g1"))
        .thenReturn(Optional.of(Group.builder().id("g1").name("g1").parentId("g2").build()));
    when(groups.findById("g2"))
        .thenReturn(Optional.of(Group.builder().id("g2").name("g2").parentId("g1").build()));

    RuleEngine engine = engine(repo, members, groups);
    List<AccessRule> rules = engine.effectiveFor("u1");

    assertThat(rules).isEmpty();
  }

  @Test
  void chainNameIsStableAndPrefixed() {
    RuleEngine engine =
        engine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            mock(GroupRepository.class));
    assertThat(engine.chainName("alice")).isEqualTo(engine.chainName("alice"));
    assertThat(engine.chainName("alice")).startsWith("PASSAGE_").hasSizeGreaterThan(4);
  }

  @Test
  void dstGroupIdResolvesToPoolRangeMatch() {
    SettingsService settings = mock(SettingsService.class);
    when(settings.groupSettings("g2"))
        .thenReturn(Map.of("static_ip_pool", "10.8.0.100-10.8.0.199"));
    UserRepository users = mock(UserRepository.class);
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            mock(GroupRepository.class),
            settings,
            users,
            mock(DomainResolver.class),
            mock(DnsOverrideService.class));

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    rule.setDstGroupId("g2");

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(rule));

    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("-m iprange --dst-range 10.8.0.100-10.8.0.199"))
        .anyMatch(cmd -> cmd.contains("-j ACCEPT"));
  }

  @Test
  void dstGroupIdInheritsPoolFromAncestor() {
    SettingsService settings = mock(SettingsService.class);
    when(settings.groupSettings("g2")).thenReturn(Map.of());
    when(settings.groupSettings("g1")).thenReturn(Map.of("static_ip_pool", "10.8.0.50-10.8.0.99"));
    GroupRepository groups = mock(GroupRepository.class);
    when(groups.findById("g2"))
        .thenReturn(Optional.of(Group.builder().id("g2").parentId("g1").build()));
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            groups,
            settings,
            mock(UserRepository.class),
            mock(DomainResolver.class),
            mock(DnsOverrideService.class));

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    rule.setDstGroupId("g2");

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(rule));

    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("-m iprange --dst-range 10.8.0.50-10.8.0.99"))
        .noneMatch(cmd -> cmd.contains("-d 10.8.0.120/32"));
  }

  @Test
  void dstGroupIdFallsBackToMemberStaticIps() {
    SettingsService settings = mock(SettingsService.class);
    when(settings.groupSettings("g2")).thenReturn(Map.of());
    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_GroupId("g2"))
        .thenReturn(List.of(new GroupMember("g2", "u1"), new GroupMember("g2", "u2")));
    UserRepository users = mock(UserRepository.class);
    when(users.findById("u1"))
        .thenReturn(
            Optional.of(User.builder().id("u1").username("bob").staticIp("10.8.0.120").build()));
    when(users.findById("u2"))
        .thenReturn(
            Optional.of(User.builder().id("u2").username("carol").staticIp("10.8.0.121").build()));
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            members,
            mock(GroupRepository.class),
            settings,
            users,
            mock(DomainResolver.class),
            mock(DnsOverrideService.class));

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.DENY, 0, true);
    rule.setDstGroupId("g2");

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(rule));

    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("-d 10.8.0.120/32"))
        .anyMatch(cmd -> cmd.contains("-d 10.8.0.121/32"))
        .anyMatch(cmd -> cmd.contains("-j DROP"));
  }

  @Test
  void unresolvableDstGroupSkipsRuleButKeepsDefaults() {
    SettingsService settings = mock(SettingsService.class);
    when(settings.groupSettings("g2")).thenReturn(Map.of());
    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_GroupId("g2")).thenReturn(List.of());
    UserRepository users = mock(UserRepository.class);
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            members,
            mock(GroupRepository.class),
            settings,
            users,
            mock(DomainResolver.class),
            mock(DnsOverrideService.class));

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.DENY, 0, true);
    rule.setDstGroupId("g2");

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(rule));

    String chain = engine.chainName("alice");
    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("iptables -N " + chain))
        .anyMatch(cmd -> cmd.contains("-j DROP"));
    assertThat(result.apply().stream().filter(cmd -> cmd.contains("-j DROP")).count())
        .isEqualTo(1L);
    assertThat(result.apply()).noneMatch(cmd -> cmd.contains("-m iprange"));
  }

  @Test
  void cidrRuleIsUnaffectedByDestinationSpecs() {
    AccessRule allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    allow.setDstCidr("10.0.0.0/8");

    RuleEngine engine =
        engine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            mock(GroupRepository.class));
    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(allow));

    assertThat(result.apply()).anyMatch(cmd -> cmd.contains("-d 10.0.0.0/8 "));
  }

  @Test
  void dstDomainResolvesToPerIpMatches() {
    DomainResolver resolver = mock(DomainResolver.class);
    when(resolver.resolve("api.github.com")).thenReturn(Set.of("140.82.112.5", "140.82.113.5"));
    RuleEngine engine = engineWithResolver(resolver);

    AccessRule allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    allow.setProtocol(AccessRule.Protocol.TCP);
    allow.setDstDomain("api.github.com");

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(allow));

    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("-d 140.82.112.5/32"))
        .anyMatch(cmd -> cmd.contains("-d 140.82.113.5/32"))
        .anyMatch(cmd -> cmd.contains("-j ACCEPT"))
        .anyMatch(cmd -> cmd.contains("-j DROP"));
    verify(resolver).resolve("api.github.com");
  }

  @Test
  void unresolvableDstDomainSkipsRuleButKeepsDefaults() {
    DomainResolver resolver = mock(DomainResolver.class);
    when(resolver.resolve("down.example.com")).thenReturn(Set.of());
    RuleEngine engine = engineWithResolver(resolver);

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.DENY, 0, true);
    rule.setDstDomain("down.example.com");

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(rule));

    String chain = engine.chainName("alice");
    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("iptables -N " + chain))
        .anyMatch(cmd -> cmd.contains("-j DROP"));
    assertThat(result.apply().stream().filter(cmd -> cmd.contains("-j DROP")).count())
        .isEqualTo(1L);
    assertThat(result.apply()).noneMatch(cmd -> cmd.contains("/32"));
  }

  @Test
  void dstDomainTakesPrecedenceOverCidrWhenBothSetOnEntity() {
    DomainResolver resolver = mock(DomainResolver.class);
    when(resolver.resolve("x.example.com")).thenReturn(Set.of("1.2.3.4"));
    RuleEngine engine = engineWithResolver(resolver);

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    rule.setDstDomain("x.example.com");
    rule.setDstCidr("10.0.0.0/8");

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(rule));

    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("-d 1.2.3.4/32"))
        .noneMatch(cmd -> cmd.contains("-d 10.0.0.0/8"));
    verify(resolver).resolve("x.example.com");
  }

  @Test
  void dstDomainUsesOverrideIpBeforeResolver() {
    DomainResolver resolver = mock(DomainResolver.class);
    DnsOverrideService overrides = mock(DnsOverrideService.class);
    when(overrides.resolveDomain("git.internal")).thenReturn(Set.of("10.10.0.5"));
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            mock(GroupRepository.class),
            mock(SettingsService.class),
            mock(UserRepository.class),
            resolver,
            overrides);

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    rule.setDstDomain("git.internal");

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(rule));

    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("-d 10.10.0.5/32"))
        .anyMatch(cmd -> cmd.contains("-j ACCEPT"));
    verify(resolver, never()).resolve("git.internal");
  }

  @Test
  void scopeDenyIpsForDeniesOutOfScopeRecordsOnly() {
    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g1", "u1")));
    GroupRepository groups = mock(GroupRepository.class);
    when(groups.findById("g1"))
        .thenReturn(Optional.of(Group.builder().id("g1").name("g1").build()));
    DnsOverrideService overrides = mock(DnsOverrideService.class);
    when(overrides.nonGlobalEnabled())
        .thenReturn(
            List.of(
                dnsRecord("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.USER, "u2"),
                dnsRecord("d2", "db.internal", "10.10.0.6", DnsRecord.Scope.GROUP, "g1"),
                dnsRecord("d3", "mon.internal", "10.10.0.7", DnsRecord.Scope.GROUP, "g2")));
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            members,
            groups,
            mock(SettingsService.class),
            mock(UserRepository.class),
            mock(DomainResolver.class),
            overrides);

    Set<String> denied = engine.scopeDenyIpsFor("u1");

    assertThat(denied).containsExactly("10.10.0.5", "10.10.0.7");
  }

  @Test
  void scopeDeniesOnlyCreatesChainWithOpenTerminal() {
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            mock(GroupRepository.class),
            mock(SettingsService.class),
            mock(UserRepository.class),
            mock(DomainResolver.class),
            mock(DnsOverrideService.class));

    RuleEngine.IptablesResult result =
        engine.iptablesFor("alice", "10.8.0.5", List.of(), Set.of("10.10.0.5"));

    String chain = engine.chainName("alice");
    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("iptables -N " + chain))
        .anyMatch(cmd -> cmd.contains("-s 10.8.0.5 -d 10.10.0.5/32 -j DROP"))
        .anyMatch(cmd -> cmd.equals("iptables -A " + chain + " -j ACCEPT"))
        .noneMatch(cmd -> cmd.equals("iptables -A " + chain + " -j DROP"));
  }

  @Test
  void scopeDeniesWithAccessRulesKeepDefaultDeny() {
    AccessRule allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    allow.setDstCidr("10.0.0.0/8");
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            mock(GroupRepository.class),
            mock(SettingsService.class),
            mock(UserRepository.class),
            mock(DomainResolver.class),
            mock(DnsOverrideService.class));

    RuleEngine.IptablesResult result =
        engine.iptablesFor("alice", "10.8.0.5", List.of(allow), Set.of("10.10.0.5"));

    String chain = engine.chainName("alice");
    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("-d 10.10.0.5/32 -j DROP"))
        .anyMatch(cmd -> cmd.equals("iptables -A " + chain + " -j DROP"))
        .noneMatch(cmd -> cmd.equals("iptables -A " + chain + " -j ACCEPT"));
  }

  private DnsRecord dnsRecord(
      String id, String hostname, String ipv4, DnsRecord.Scope scope, String scopeId) {
    return DnsRecord.builder()
        .id(id)
        .hostname(hostname)
        .ipv4(ipv4)
        .scope(scope)
        .scopeId(scopeId)
        .enabled(true)
        .createdAt(Instant.now())
        .build();
  }

  private DnsRecord dnsRecord6(
      String id, String hostname, String ipv4, String ipv6, DnsRecord.Scope scope, String scopeId) {
    return DnsRecord.builder()
        .id(id)
        .hostname(hostname)
        .ipv4(ipv4)
        .ipv6(ipv6)
        .scope(scope)
        .scopeId(scopeId)
        .enabled(true)
        .createdAt(Instant.now())
        .build();
  }

  private RuleEngine bareEngine() {
    return engine(
        mock(AccessRuleRepository.class),
        mock(GroupMemberRepository.class),
        mock(GroupRepository.class));
  }

  private RuleEngine engineWithOverrides(DnsOverrideService overrides) {
    return new RuleEngine(
        mock(AccessRuleRepository.class),
        mock(GroupMemberRepository.class),
        mock(GroupRepository.class),
        mock(SettingsService.class),
        mock(UserRepository.class),
        mock(DomainResolver.class),
        overrides);
  }

  private RuleEngine engineWithResolverAndOverrides(
      DomainResolver resolver, DnsOverrideService overrides) {
    return new RuleEngine(
        mock(AccessRuleRepository.class),
        mock(GroupMemberRepository.class),
        mock(GroupRepository.class),
        mock(SettingsService.class),
        mock(UserRepository.class),
        resolver,
        overrides);
  }

  @Test
  void ipv6ChainMirrorsIpv4AndSkipsForeignFamilyDestinations() {
    AccessRule v4Allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    v4Allow.setDstCidr("10.0.0.0/8");
    AccessRule v6Allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 1, true);
    v6Allow.setDstCidr("2001:db8::/32");
    RuleEngine engine = bareEngine();

    RuleEngine.IptablesResult result =
        engine.iptablesFor(
            "alice", "10.8.0.5", "fd00:1::5", List.of(v4Allow, v6Allow), Set.of(), Set.of(), true);

    String chain = engine.chainName("alice");
    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("iptables -N " + chain))
        .anyMatch(cmd -> cmd.contains("-d 10.0.0.0/8"))
        .noneMatch(cmd -> cmd.contains("2001:db8::"))
        .anyMatch(cmd -> cmd.equals("iptables -A " + chain + " -j DROP"));
    assertThat(result.apply6())
        .anyMatch(cmd -> cmd.contains("ip6tables -N " + chain + "6"))
        .anyMatch(cmd -> cmd.contains("-s fd00:1::5"))
        .anyMatch(cmd -> cmd.contains("-d 2001:db8::/32"))
        .noneMatch(cmd -> cmd.contains("10.0.0.0/8"))
        .anyMatch(cmd -> cmd.equals("ip6tables -A " + chain + "6 -j DROP"));
    assertThat(result.remove6()).anyMatch(cmd -> cmd.contains("-X " + chain + "6"));
  }

  @Test
  void ipv6ChainNotEmittedWhenVirtualIp6Blank() {
    AccessRule allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    allow.setDstCidr("10.0.0.0/8");
    RuleEngine engine = bareEngine();

    RuleEngine.IptablesResult result =
        engine.iptablesFor("alice", "10.8.0.5", "", List.of(allow), Set.of(), Set.of(), true);

    assertThat(result.apply()).isNotEmpty();
    assertThat(result.apply6()).isEmpty();
  }

  @Test
  void ipv6ChainNotEmittedWhenDualStackDisabled() {
    AccessRule allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    allow.setDstCidr("10.0.0.0/8");
    RuleEngine engine = bareEngine();

    RuleEngine.IptablesResult result =
        engine.iptablesFor(
            "alice", "10.8.0.5", "fd00:1::5", List.of(allow), Set.of(), Set.of(), false);

    assertThat(result.apply()).isNotEmpty();
    assertThat(result.apply6()).isEmpty();
  }

  @Test
  void ipv6ScopeDeniesOnlyCreateOpenChains() {
    RuleEngine engine = bareEngine();

    RuleEngine.IptablesResult result =
        engine.iptablesFor(
            "alice",
            "10.8.0.5",
            "fd00:1::5",
            List.of(),
            Set.of("10.10.0.5"),
            Set.of("fd00:10::5"),
            true);

    String chain = engine.chainName("alice");
    assertThat(result.apply())
        .anyMatch(cmd -> cmd.equals("iptables -A " + chain + " -j ACCEPT"))
        .anyMatch(cmd -> cmd.contains("-d 10.10.0.5/32 -j DROP"))
        .noneMatch(cmd -> cmd.equals("iptables -A " + chain + " -j DROP"));
    assertThat(result.apply6())
        .anyMatch(cmd -> cmd.equals("ip6tables -A " + chain + "6 -j ACCEPT"))
        .anyMatch(cmd -> cmd.contains("-d fd00:10::5/128 -j DROP"))
        .noneMatch(cmd -> cmd.equals("ip6tables -A " + chain + "6 -j DROP"));
  }

  @Test
  void nullVirtualIpOmitsSourceMatch() {
    AccessRule allow = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    allow.setDstCidr("10.0.0.0/8");
    RuleEngine engine = bareEngine();

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", null, List.of(allow));

    String chain = engine.chainName("alice");
    assertThat(result.apply())
        .anyMatch(cmd -> cmd.contains("iptables -I FORWARD -j " + chain))
        .noneMatch(cmd -> cmd.contains("-s "));
  }

  @Test
  void nullDeniedIpsAndNoRulesYieldEmptyResult() {
    RuleEngine engine = bareEngine();

    RuleEngine.IptablesResult result = engine.iptablesFor("alice", "10.8.0.5", List.of(), null);

    assertThat(result.apply()).isEmpty();
    assertThat(result.remove()).isEmpty();
    assertThat(result.apply6()).isEmpty();
  }

  @Test
  void dstGroupIpv6FallsBackToMemberStaticIpv6AndSkipsMissingUsers() {
    SettingsService settings = mock(SettingsService.class);
    when(settings.groupSettings("g2")).thenReturn(Map.of());
    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_GroupId("g2"))
        .thenReturn(
            List.of(
                new GroupMember("g2", "u1"),
                new GroupMember("g2", "u2"),
                new GroupMember("g2", "u3")));
    UserRepository users = mock(UserRepository.class);
    when(users.findById("u1"))
        .thenReturn(
            Optional.of(User.builder().id("u1").username("bob").staticIpv6("fd00:1::100").build()));
    when(users.findById("u2"))
        .thenReturn(
            Optional.of(
                User.builder().id("u2").username("carol").staticIpv6("fd00:1::101").build()));
    when(users.findById("u3")).thenReturn(Optional.empty());
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            members,
            mock(GroupRepository.class),
            settings,
            users,
            mock(DomainResolver.class),
            mock(DnsOverrideService.class));

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    rule.setDstGroupId("g2");

    RuleEngine.IptablesResult result =
        engine.iptablesFor(
            "alice", "10.8.0.5", "fd00:1::5", List.of(rule), Set.of(), Set.of(), true);

    assertThat(result.apply())
        .noneMatch(cmd -> cmd.contains("fd00:1::100"))
        .anyMatch(cmd -> cmd.contains("-j DROP"));
    assertThat(result.apply6())
        .anyMatch(cmd -> cmd.contains("-d fd00:1::100/128"))
        .anyMatch(cmd -> cmd.contains("-d fd00:1::101/128"));
    verify(users, times(2)).findById("u3");
  }

  @Test
  void dstGroupIpv6InheritsPoolFromAncestor() {
    SettingsService settings = mock(SettingsService.class);
    when(settings.groupSettings("g2")).thenReturn(Map.of());
    when(settings.groupSettings("g1"))
        .thenReturn(Map.of("static_ipv6_pool", "fd00:1::100-fd00:1::199"));
    GroupRepository groups = mock(GroupRepository.class);
    when(groups.findById("g2"))
        .thenReturn(Optional.of(Group.builder().id("g2").parentId("g1").build()));
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            mock(GroupMemberRepository.class),
            groups,
            settings,
            mock(UserRepository.class),
            mock(DomainResolver.class),
            mock(DnsOverrideService.class));

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    rule.setDstGroupId("g2");

    RuleEngine.IptablesResult result =
        engine.iptablesFor(
            "alice", "10.8.0.5", "fd00:1::5", List.of(rule), Set.of(), Set.of(), true);

    assertThat(result.apply6())
        .anyMatch(cmd -> cmd.contains("-m iprange --dst-range fd00:1::100-fd00:1::199"));
  }

  @Test
  void dstDomainUsesIpv6OverrideBeforeResolver() {
    DomainResolver resolver = mock(DomainResolver.class);
    DnsOverrideService overrides = mock(DnsOverrideService.class);
    when(overrides.resolveDomainIpv6("git.internal")).thenReturn(Set.of("fd00:10::5"));
    RuleEngine engine = engineWithResolverAndOverrides(resolver, overrides);

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    rule.setDstDomain("git.internal");

    RuleEngine.IptablesResult result =
        engine.iptablesFor(
            "alice", "10.8.0.5", "fd00:1::5", List.of(rule), Set.of(), Set.of(), true);

    assertThat(result.apply6()).anyMatch(cmd -> cmd.contains("-d fd00:10::5/128"));
    verify(resolver, never()).resolveIpv6("git.internal");
  }

  @Test
  void dstDomainFallsBackToResolverForIpv6() {
    DomainResolver resolver = mock(DomainResolver.class);
    when(resolver.resolveIpv6("example.com")).thenReturn(Set.of("2606:4700::1111"));
    DnsOverrideService overrides = mock(DnsOverrideService.class);
    when(overrides.resolveDomainIpv6("example.com")).thenReturn(Set.of());
    RuleEngine engine = engineWithResolverAndOverrides(resolver, overrides);

    AccessRule rule = rule(TargetType.GLOBAL, null, Action.ALLOW, 0, true);
    rule.setDstDomain("example.com");

    RuleEngine.IptablesResult result =
        engine.iptablesFor(
            "alice", "10.8.0.5", "fd00:1::5", List.of(rule), Set.of(), Set.of(), true);

    assertThat(result.apply6()).anyMatch(cmd -> cmd.contains("-d 2606:4700::1111/128"));
    verify(resolver).resolveIpv6("example.com");
  }

  @Test
  void scopeDenyIpv6ForDeniesOutOfScopeRecordsWithIpv6Only() {
    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g1", "u1")));
    GroupRepository groups = mock(GroupRepository.class);
    when(groups.findById("g1"))
        .thenReturn(Optional.of(Group.builder().id("g1").name("g1").build()));
    DnsOverrideService overrides = mock(DnsOverrideService.class);
    when(overrides.nonGlobalEnabled())
        .thenReturn(
            List.of(
                dnsRecord6(
                    "d1", "git.internal", "10.10.0.5", "fd00:10::5", DnsRecord.Scope.USER, "u2"),
                dnsRecord6(
                    "d2", "db.internal", "10.10.0.6", "fd00:10::6", DnsRecord.Scope.GROUP, "g1"),
                dnsRecord6("d3", "no6.internal", "10.10.0.7", "", DnsRecord.Scope.GROUP, "g2")));
    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            members,
            groups,
            mock(SettingsService.class),
            mock(UserRepository.class),
            mock(DomainResolver.class),
            overrides);

    Set<String> denied = engine.scopeDenyIpv6For("u1");

    assertThat(denied).containsExactly("fd00:10::5");
  }

  @Test
  void scopeDenyIpsForAllowsUserOwnRecord() {
    DnsOverrideService overrides = mock(DnsOverrideService.class);
    when(overrides.nonGlobalEnabled())
        .thenReturn(
            List.of(dnsRecord("d1", "own.internal", "10.10.0.5", DnsRecord.Scope.USER, "u1")));

    RuleEngine engine = engineWithOverrides(overrides);

    assertThat(engine.scopeDenyIpsFor("u1")).isEmpty();
  }

  @Test
  void scopeDenyIpsForDeniesRecordsWhenUserHasNoGroups() {
    GroupMemberRepository members = mock(GroupMemberRepository.class);
    when(members.findById_UserId("u1")).thenReturn(List.of());
    DnsOverrideService overrides = mock(DnsOverrideService.class);
    when(overrides.nonGlobalEnabled())
        .thenReturn(
            List.of(dnsRecord("d1", "db.internal", "10.10.0.6", DnsRecord.Scope.GROUP, "g1")));

    RuleEngine engine =
        new RuleEngine(
            mock(AccessRuleRepository.class),
            members,
            mock(GroupRepository.class),
            mock(SettingsService.class),
            mock(UserRepository.class),
            mock(DomainResolver.class),
            overrides);

    assertThat(engine.scopeDenyIpsFor("u1")).containsExactly("10.10.0.6");
  }
}
