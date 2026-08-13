package com.opnl.vpn.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.access.AccessRule;
import com.opnl.vpn.access.AccessRuleRepository;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DnsScopeConflictServiceTest {

  private final AccessRuleRepository rules = mock(AccessRuleRepository.class);
  private final GroupMemberRepository members = mock(GroupMemberRepository.class);
  private final GroupRepository groups = mock(GroupRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final DnsRecordRepository records = mock(DnsRecordRepository.class);

  private final DnsScopeConflictService service =
      new DnsScopeConflictService(rules, members, groups, users, records);

  private DnsRecord record(
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

  private AccessRule rule(
      AccessRule.TargetType targetType,
      String targetId,
      AccessRule.Action action,
      String dstCidr,
      boolean enabled) {
    return AccessRule.builder()
        .id("r1")
        .targetType(targetType)
        .targetId(targetId)
        .action(action)
        .dstCidr(dstCidr)
        .enabled(enabled)
        .priority(0)
        .createdAt(Instant.now())
        .build();
  }

  private void group(String id, String parentId) {
    when(groups.findById(id))
        .thenReturn(Optional.of(Group.builder().id(id).name(id).parentId(parentId).build()));
  }

  @Test
  void globalRecordNeverWarns() {
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.GLOBAL,
                    null,
                    AccessRule.Action.ALLOW,
                    "10.0.0.0/8",
                    true)));
    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GLOBAL, null)))
        .isEmpty();
  }

  @Test
  void groupScopedRecordWarnsOnGlobalAllow() {
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.GLOBAL,
                    null,
                    AccessRule.Action.ALLOW,
                    "10.0.0.0/8",
                    true)));
    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GROUP, "g1")))
        .singleElement()
        .asString()
        .contains("10.10.0.5");
  }

  @Test
  void groupScopedRecordNoWarnWhenAllowTargetInsideScope() {
    group("g1", null);
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.GROUP,
                    "h1",
                    AccessRule.Action.ALLOW,
                    "10.0.0.0/8",
                    true)));
    when(members.findById_GroupId("h1")).thenReturn(List.of(new GroupMember("h1", "u1")));
    when(members.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g1", "u1")));

    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GROUP, "g1")))
        .isEmpty();
  }

  @Test
  void groupScopedRecordWarnsWhenAllowGroupHasOutOfScopeMember() {
    group("g1", null);
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.GROUP,
                    "h1",
                    AccessRule.Action.ALLOW,
                    "10.0.0.0/8",
                    true)));
    when(members.findById_GroupId("h1"))
        .thenReturn(List.of(new GroupMember("h1", "u1"), new GroupMember("h1", "u2")));
    when(members.findById_UserId("u1")).thenReturn(List.of(new GroupMember("g1", "u1")));
    when(members.findById_UserId("u2")).thenReturn(List.of(new GroupMember("g9", "u2")));

    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GROUP, "g1")))
        .hasSize(1);
  }

  @Test
  void userScopedRecordWarnsOnAnyOtherUserAllow() {
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.USER,
                    "u2",
                    AccessRule.Action.ALLOW,
                    "10.0.0.0/8",
                    true)));
    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.USER, "u1")))
        .hasSize(1);
  }

  @Test
  void userScopedRecordNoWarnWhenAllowTargetsSameUser() {
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.USER,
                    "u1",
                    AccessRule.Action.ALLOW,
                    "10.0.0.0/8",
                    true)));
    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.USER, "u1")))
        .isEmpty();
  }

  @Test
  void userScopedRecordNoWarnWhenAllowGroupOnlyContainsOwner() {
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.GROUP,
                    "h1",
                    AccessRule.Action.ALLOW,
                    "10.0.0.0/8",
                    true)));
    when(members.findById_GroupId("h1")).thenReturn(List.of(new GroupMember("h1", "u1")));
    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.USER, "u1")))
        .isEmpty();
  }

  @Test
  void denyAndDisabledRulesNeverWarn() {
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.GLOBAL, null, AccessRule.Action.DENY, "10.0.0.0/8", true),
                rule(
                    AccessRule.TargetType.GLOBAL,
                    null,
                    AccessRule.Action.ALLOW,
                    "10.0.0.0/8",
                    false)));
    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GROUP, "g1")))
        .isEmpty();
  }

  @Test
  void allowOnlyWarnsWhenCidrActuallyCoversAddress() {
    when(rules.findAll())
        .thenReturn(
            List.of(
                rule(
                    AccessRule.TargetType.GLOBAL,
                    null,
                    AccessRule.Action.ALLOW,
                    "10.20.0.0/24",
                    true)));
    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GROUP, "g1")))
        .isEmpty();
  }

  @Test
  void domainRuleMatchingHostnameWarns() {
    AccessRule domainRule =
        AccessRule.builder()
            .id("r2")
            .targetType(AccessRule.TargetType.GLOBAL)
            .action(AccessRule.Action.ALLOW)
            .dstDomain("git.internal")
            .enabled(true)
            .priority(0)
            .createdAt(Instant.now())
            .build();
    when(rules.findAll()).thenReturn(List.of(domainRule));
    assertThat(
            service.warningsForRecord(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GROUP, "g1")))
        .hasSize(1);
  }

  @Test
  void warningsForRuleFlagsScopedRecordsCoveredByCidr() {
    AccessRule allow =
        rule(AccessRule.TargetType.GLOBAL, null, AccessRule.Action.ALLOW, "10.0.0.0/8", true);
    when(records.findByEnabledTrue())
        .thenReturn(
            List.of(
                record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GROUP, "g1"),
                record("d2", "public.internal", "10.10.0.6", DnsRecord.Scope.GLOBAL, null),
                record("d3", "other.internal", "172.16.0.5", DnsRecord.Scope.GROUP, "g1")));

    assertThat(service.warningsForRule(allow)).singleElement().asString().contains("git.internal");
  }

  @Test
  void warningsForRuleEmptyForDenyGroupTargetAndGroupDestination() {
    AccessRule deny =
        rule(AccessRule.TargetType.GLOBAL, null, AccessRule.Action.DENY, "10.0.0.0/8", true);
    AccessRule groupTarget =
        rule(AccessRule.TargetType.GROUP, "h1", AccessRule.Action.ALLOW, "10.0.0.0/8", true);
    AccessRule groupDest =
        AccessRule.builder()
            .id("r3")
            .targetType(AccessRule.TargetType.GLOBAL)
            .action(AccessRule.Action.ALLOW)
            .dstGroupId("g1")
            .enabled(true)
            .priority(0)
            .createdAt(Instant.now())
            .build();
    when(records.findByEnabledTrue())
        .thenReturn(
            List.of(record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.GROUP, "g1")));

    assertThat(service.warningsForRule(deny)).isEmpty();
    assertThat(service.warningsForRule(groupDest)).isEmpty();
    when(members.findById_GroupId("h1")).thenReturn(List.of());
    assertThat(service.warningsForRule(groupTarget)).isEmpty();
  }

  @Test
  void warningsForRuleResolvesDisplayNames() {
    AccessRule allow =
        rule(AccessRule.TargetType.USER, "u2", AccessRule.Action.ALLOW, "10.0.0.0/8", true);
    when(records.findByEnabledTrue())
        .thenReturn(List.of(record("d1", "git.internal", "10.10.0.5", DnsRecord.Scope.USER, "u1")));
    when(users.findById("u2"))
        .thenReturn(Optional.of(User.builder().id("u2").username("bob").build()));
    when(users.findById("u1"))
        .thenReturn(Optional.of(User.builder().id("u1").username("alice").build()));

    assertThat(service.warningsForRule(allow))
        .singleElement()
        .asString()
        .contains("git.internal")
        .contains("alice");
  }
}
