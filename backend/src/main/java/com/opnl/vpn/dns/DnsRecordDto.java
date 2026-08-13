package com.opnl.vpn.dns;

import com.opnl.vpn.dns.DnsRecord.Scope;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Admin-facing DNS override representation. */
public record DnsRecordDto(
    String id,
    @Size(max = 253, message = "hostname must not exceed 253 characters")
        @Pattern(
            regexp =
                "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]*[a-z0-9])?)(\\.([a-z0-9]([a-z0-9-]*[a-z0-9])?))*$",
            message =
                "hostname must be a valid hostname/domain like git.internal "
                    + "(lowercase letters, digits, dots and hyphens)")
        String hostname,
    @Pattern(
            regexp =
                "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})$",
            message = "ipv4 must be a valid IPv4 address like 10.10.0.5")
        String ipv4,
    @NotNull Scope scope,
    String scopeId,
    String scopeName,
    Boolean enabled,
    String createdAt) {

  /** A scope target is required for GROUP/USER records and forbidden for GLOBAL records. */
  @AssertTrue(message = "scopeId is required for GROUP/USER scope and forbidden for GLOBAL")
  public boolean isScopeValid() {
    if (scope == null) {
      return true;
    }
    boolean needsTarget = scope == Scope.GROUP || scope == Scope.USER;
    boolean hasTarget = scopeId != null && !scopeId.isBlank();
    return needsTarget == hasTarget;
  }

  public static DnsRecordDto from(DnsRecord record, String scopeName) {
    return new DnsRecordDto(
        record.getId(),
        record.getHostname(),
        record.getIpv4(),
        record.getScope(),
        record.getScopeId(),
        scopeName,
        record.isEnabled(),
        record.getCreatedAt() == null ? null : record.getCreatedAt().toString());
  }
}
