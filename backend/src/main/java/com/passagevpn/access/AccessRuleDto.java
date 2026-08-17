package com.passagevpn.access;

import com.passagevpn.access.AccessRule.Action;
import com.passagevpn.access.AccessRule.Protocol;
import com.passagevpn.access.AccessRule.TargetType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

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
    @Size(max = 253, message = "dstDomain must not exceed 253 characters")
        @Pattern(
            regexp =
                "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]*[a-z0-9])?)(\\.([a-z0-9]([a-z0-9-]*[a-z0-9])?))*$",
            message =
                "dstDomain must be a valid hostname/domain like api.github.com "
                    + "(lowercase letters, digits, dots and hyphens)")
        String dstDomain,
    Integer dstPort,
    Boolean enabled,
    Integer priority,
    List<String> warnings) {

  /** A destination must be a CIDR, a group or a domain — never more than one. */
  @AssertTrue(message = "dstCidr, dstGroupId and dstDomain are mutually exclusive")
  public boolean isDestinationValid() {
    return (dstCidr == null ? 0 : 1) + (dstGroupId == null ? 0 : 1) + (dstDomain == null ? 0 : 1)
        <= 1;
  }

  public static AccessRuleDto from(AccessRule rule, String targetName, String dstGroupName) {
    return from(rule, targetName, dstGroupName, List.of());
  }

  public static AccessRuleDto from(
      AccessRule rule, String targetName, String dstGroupName, List<String> warnings) {
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
        rule.getDstDomain(),
        rule.getDstPort(),
        rule.isEnabled(),
        rule.getPriority(),
        warnings == null ? List.of() : warnings);
  }
}
