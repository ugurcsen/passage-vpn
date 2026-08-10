package com.opnl.vpn.access;

import com.opnl.vpn.access.AccessRule.Action;
import com.opnl.vpn.access.AccessRule.Protocol;
import com.opnl.vpn.access.AccessRule.TargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Admin-facing access rule representation. */
public record AccessRuleDto(
    String id,
    @NotNull TargetType targetType,
    String targetId,
    String targetName,
    @NotNull Action action,
    Protocol protocol,
    @Pattern(
            regexp = "^\\d{1,3}(\\.\\d{1,3}){3}/(3[0-2]|[0-2]?[0-9])$",
            message = "dstCidr must be a valid CIDR like 192.168.0.0/24")
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
