package com.opnl.vpn.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.access.AccessRule.Action;
import com.opnl.vpn.access.AccessRule.TargetType;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        repo, members, groups, mock(SettingsService.class), mock(UserRepository.class));
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
    assertThat(chain).startsWith("OPNL_").hasSizeGreaterThan(4);
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
    assertThat(engine.chainName("alice")).startsWith("OPNL_").hasSizeGreaterThan(4);
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
            users);

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
            mock(UserRepository.class));

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
            users);

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
            users);

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
}
