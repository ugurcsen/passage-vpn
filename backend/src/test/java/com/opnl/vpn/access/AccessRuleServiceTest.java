package com.opnl.vpn.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.access.AccessRule.Action;
import com.opnl.vpn.access.AccessRule.Protocol;
import com.opnl.vpn.access.AccessRule.TargetType;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.network.DnsmasqConfigService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccessRuleServiceTest {

  private AccessRuleRepository ruleRepository;
  private UserRepository userRepository;
  private GroupRepository groupRepository;
  private RuleEngine ruleEngine;
  private DnsmasqConfigService dnsmasqConfigService;
  private AccessRuleService service;

  @BeforeEach
  void setUp() {
    ruleRepository = mock(AccessRuleRepository.class);
    userRepository = mock(UserRepository.class);
    groupRepository = mock(GroupRepository.class);
    ruleEngine = mock(RuleEngine.class);
    dnsmasqConfigService = mock(DnsmasqConfigService.class);
    service =
        new AccessRuleService(
            ruleRepository,
            userRepository,
            groupRepository,
            ruleEngine,
            mock(AuditLogService.class),
            dnsmasqConfigService);
    when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private AccessRuleDto dto(TargetType type, String targetId, Action action) {
    return new AccessRuleDto(
        null,
        type,
        targetId,
        null,
        action,
        Protocol.TCP,
        "10.0.0.0/8",
        null,
        null,
        null,
        443,
        true,
        null);
  }

  @Test
  void createAssignsNextPriority() {
    when(ruleRepository.findAll())
        .thenReturn(
            List.of(
                AccessRule.builder().id("a").priority(3).build(),
                AccessRule.builder().id("b").priority(7).build()));

    AccessRuleDto created = service.create(dto(TargetType.GLOBAL, null, Action.ALLOW));

    assertThat(created.priority()).isEqualTo(8);
    assertThat(created.targetType()).isEqualTo(TargetType.GLOBAL);
  }

  @Test
  void createAssignsManualIdBeforePersist() {
    when(ruleRepository.findAll()).thenReturn(List.of());

    service.create(dto(TargetType.GLOBAL, null, Action.ALLOW));

    ArgumentCaptor<AccessRule> captor = ArgumentCaptor.forClass(AccessRule.class);
    verify(ruleRepository).save(captor.capture());
    assertThat(captor.getValue().getId()).isNotBlank();
    assertThat(captor.getValue().getCreatedAt()).isNotNull();
  }

  @Test
  void createValidatesUserTarget() {
    when(userRepository.findById("ghost")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(dto(TargetType.USER, "ghost", Action.ALLOW)))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "user_not_found");
    verify(ruleRepository, never()).save(any());
  }

  @Test
  void createValidatesGroupTarget() {
    when(groupRepository.findById("ghost")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(dto(TargetType.GROUP, "ghost", Action.ALLOW)))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "group_not_found");
  }

  @Test
  void createRejectsMissingTargetIdForNonGlobal() {
    assertThatThrownBy(() -> service.create(dto(TargetType.USER, null, Action.ALLOW)))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "missing_target");
  }

  @Test
  void createResolvesTargetNameForUser() {
    User alice = User.builder().id("u1").username("alice").createdAt(Instant.now()).build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));
    when(userRepository.findAll()).thenReturn(List.of(alice));
    when(ruleRepository.findAll()).thenReturn(List.of());

    AccessRuleDto created = service.create(dto(TargetType.USER, "u1", Action.DENY));

    assertThat(created.targetName()).isEqualTo("alice");
  }

  @Test
  void updateModifiesRule() {
    AccessRule existing =
        AccessRule.builder()
            .id("r1")
            .targetType(TargetType.GLOBAL)
            .action(Action.ALLOW)
            .priority(2)
            .enabled(true)
            .build();
    when(ruleRepository.findById("r1")).thenReturn(Optional.of(existing));

    AccessRuleDto updated = service.update("r1", dto(TargetType.GLOBAL, null, Action.DENY));

    assertThat(updated.action()).isEqualTo(Action.DENY);
    assertThat(existing.getDstPort()).isEqualTo(443);
  }

  @Test
  void updateThrowsWhenMissing() {
    when(ruleRepository.findById("ghost")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update("ghost", dto(TargetType.GLOBAL, null, Action.ALLOW)))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "rule_not_found");
  }

  @Test
  void deleteRemovesRule() {
    when(ruleRepository.findById("r1"))
        .thenReturn(Optional.of(AccessRule.builder().id("r1").build()));
    service.delete("r1");
    verify(ruleRepository).deleteById("r1");
  }

  @Test
  void setEnabledToggles() {
    AccessRule existing =
        AccessRule.builder().id("r1").targetType(TargetType.GLOBAL).enabled(false).build();
    when(ruleRepository.findById("r1")).thenReturn(Optional.of(existing));

    AccessRuleDto toggled = service.setEnabled("r1", true);

    assertThat(toggled.enabled()).isTrue();
  }

  @Test
  void iptablesForDelegatesToEngine() {
    RuleEngine.IptablesResult expected =
        new RuleEngine.IptablesResult(List.of("apply"), List.of("remove"));
    when(ruleEngine.effectiveFor("u1")).thenReturn(List.of());
    when(ruleEngine.scopeDenyIpsFor("u1")).thenReturn(Set.of());
    when(ruleEngine.iptablesFor("alice", "10.8.0.5", List.of(), Set.of())).thenReturn(expected);

    RuleEngine.IptablesResult result = service.iptablesFor("alice", "10.8.0.5", "u1");

    assertThat(result.apply()).containsExactly("apply");
    verify(ruleEngine).effectiveFor("u1");
    verify(ruleEngine).scopeDenyIpsFor("u1");
  }

  @Test
  void listResolvesTargetNamesForGroups() {
    when(ruleRepository.findAll())
        .thenReturn(
            List.of(
                AccessRule.builder()
                    .id("r1")
                    .targetType(TargetType.GROUP)
                    .targetId("g1")
                    .action(Action.ALLOW)
                    .priority(1)
                    .build()));
    when(userRepository.findAll()).thenReturn(List.of());
    when(groupRepository.findAll())
        .thenReturn(List.of(Group.builder().id("g1").name("devs").build()));

    List<AccessRuleDto> rules = service.list();

    assertThat(rules).hasSize(1);
    assertThat(rules.get(0).targetName()).isEqualTo("devs");
  }

  @Test
  void createStoresAndResolvesDstGroup() {
    Group devs = Group.builder().id("g2").name("devs").build();
    when(groupRepository.findById("g2")).thenReturn(Optional.of(devs));
    when(ruleRepository.findAll()).thenReturn(List.of());

    AccessRuleDto dto =
        new AccessRuleDto(
            null,
            TargetType.GLOBAL,
            null,
            null,
            Action.ALLOW,
            null,
            null,
            "g2",
            null,
            null,
            null,
            true,
            null);
    AccessRuleDto created = service.create(dto);

    assertThat(created.dstGroupId()).isEqualTo("g2");
    assertThat(created.dstGroupName()).isEqualTo("devs");
  }

  @Test
  void createValidatesDstGroup() {
    when(groupRepository.findById("ghost")).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.create(
                    new AccessRuleDto(
                        null,
                        TargetType.GLOBAL,
                        null,
                        null,
                        Action.ALLOW,
                        null,
                        null,
                        "ghost",
                        null,
                        null,
                        null,
                        true,
                        null)))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "dst_group_not_found");
  }

  @Test
  void updateAppliesDstGroup() {
    AccessRule existing =
        AccessRule.builder().id("r1").targetType(TargetType.GLOBAL).action(Action.ALLOW).build();
    when(ruleRepository.findById("r1")).thenReturn(Optional.of(existing));
    when(groupRepository.findById("g2"))
        .thenReturn(Optional.of(Group.builder().id("g2").name("devs").build()));

    AccessRuleDto updated =
        service.update(
            "r1",
            new AccessRuleDto(
                null,
                TargetType.GLOBAL,
                null,
                null,
                Action.DENY,
                null,
                null,
                "g2",
                null,
                null,
                null,
                true,
                null));

    assertThat(updated.dstGroupId()).isEqualTo("g2");
    assertThat(existing.getDstGroupId()).isEqualTo("g2");
  }

  @Test
  void setEnabledPreservesDstGroupName() {
    AccessRule existing =
        AccessRule.builder()
            .id("r1")
            .targetType(TargetType.GLOBAL)
            .action(Action.ALLOW)
            .dstGroupId("g2")
            .enabled(true)
            .build();
    when(ruleRepository.findById("r1")).thenReturn(Optional.of(existing));
    when(groupRepository.findById("g2"))
        .thenReturn(Optional.of(Group.builder().id("g2").name("devs").build()));

    AccessRuleDto updated = service.setEnabled("r1", false);

    assertThat(updated.dstGroupId()).isEqualTo("g2");
    assertThat(updated.dstGroupName()).isEqualTo("devs");
    assertThat(existing.isEnabled()).isFalse();
  }

  @Test
  void createStoresAndReturnsDstDomain() {
    when(ruleRepository.findAll()).thenReturn(List.of());

    AccessRuleDto created =
        service.create(
            new AccessRuleDto(
                null,
                TargetType.GLOBAL,
                null,
                null,
                Action.ALLOW,
                Protocol.TCP,
                null,
                null,
                null,
                "api.github.com",
                443,
                true,
                null));

    assertThat(created.dstDomain()).isEqualTo("api.github.com");
  }

  @Test
  void updateAppliesDstDomainAndRefreshesDnsmasq() {
    AccessRule existing =
        AccessRule.builder().id("r1").targetType(TargetType.GLOBAL).action(Action.ALLOW).build();
    when(ruleRepository.findById("r1")).thenReturn(Optional.of(existing));

    AccessRuleDto updated =
        service.update(
            "r1",
            new AccessRuleDto(
                null,
                TargetType.GLOBAL,
                null,
                null,
                Action.DENY,
                null,
                null,
                null,
                null,
                "blocked.example.com",
                null,
                true,
                null));

    assertThat(updated.dstDomain()).isEqualTo("blocked.example.com");
    assertThat(existing.getDstDomain()).isEqualTo("blocked.example.com");
    verify(dnsmasqConfigService).refresh();
  }

  @Test
  void everyMutationRefreshesDnsmasqConfig() {
    when(ruleRepository.findAll()).thenReturn(List.of());
    service.create(dto(TargetType.GLOBAL, null, Action.ALLOW));
    verify(dnsmasqConfigService).refresh();
  }
}
