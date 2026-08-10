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
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccessRuleServiceTest {

  private AccessRuleRepository ruleRepository;
  private UserRepository userRepository;
  private GroupRepository groupRepository;
  private RuleEngine ruleEngine;
  private AccessRuleService service;

  @BeforeEach
  void setUp() {
    ruleRepository = mock(AccessRuleRepository.class);
    userRepository = mock(UserRepository.class);
    groupRepository = mock(GroupRepository.class);
    ruleEngine = mock(RuleEngine.class);
    service = new AccessRuleService(ruleRepository, userRepository, groupRepository, ruleEngine);
    when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private AccessRuleDto dto(TargetType type, String targetId, Action action) {
    return new AccessRuleDto(
        null, type, targetId, null, action, Protocol.TCP, "10.0.0.0/8", 443, true, null);
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
    when(ruleEngine.iptablesFor("alice", "10.8.0.5", List.of())).thenReturn(expected);

    RuleEngine.IptablesResult result = service.iptablesFor("alice", "10.8.0.5", "u1");

    assertThat(result.apply()).containsExactly("apply");
    verify(ruleEngine).effectiveFor("u1");
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
}
