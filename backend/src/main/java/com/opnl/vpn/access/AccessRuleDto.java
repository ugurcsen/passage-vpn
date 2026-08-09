package com.opnl.vpn.access;

import com.opnl.vpn.access.AccessRule.Action;
import com.opnl.vpn.access.AccessRule.Protocol;
import com.opnl.vpn.access.AccessRule.TargetType;
import jakarta.validation.constraints.NotNull;

/** Admin-facing access rule representation. */
public record AccessRuleDto(
    String id,
    @NotNull TargetType targetType,
    String targetId,
    String targetName,
    @NotNull Action action,
    Protocol protocol,
    String dstCidr,
    Integer dstPort,
    Boolean enabled,
    Integer priority) {

  public static AccessRuleDto from(AccessRule rule, String targetName) {
    return new AccessRuleDto(
        rule.getId(),
        rule.getTargetType(),
        rule.getTargetId(),
        targetName,
        rule.getAction(),
        rule.getProtocol(),
        rule.getDstCidr(),
        rule.getDstPort(),
        rule.isEnabled(),
        rule.getPriority());
  }
}
