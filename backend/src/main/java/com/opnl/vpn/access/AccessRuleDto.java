package com.opnl.vpn.access;

import com.opnl.vpn.access.AccessRule.Action;
import com.opnl.vpn.access.AccessRule.Protocol;
import com.opnl.vpn.access.AccessRule.TargetType;
import jakarta.validation.constraints.AssertTrue;
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
    String dstGroupId,
    String dstGroupName,
    Integer dstPort,
    Boolean enabled,
    Integer priority) {

  /** A destination must be either a CIDR or a group, never both. */
  @AssertTrue(message = "dstCidr and dstGroupId are mutually exclusive")
  public boolean isDestinationValid() {
    return dstCidr == null || dstGroupId == null;
  }

  public static AccessRuleDto from(AccessRule rule, String targetName, String dstGroupName) {
    return new AccessRuleDto(
        rule.getId(),
        rule.getTargetType(),
        rule.getTargetId(),
        targetName,
        rule.getAction(),
        rule.getProtocol(),
        rule.getDstCidr(),
        rule.getDstGroupId(),
        dstGroupName,
        rule.getDstPort(),
        rule.isEnabled(),
        rule.getPriority());
  }
}
