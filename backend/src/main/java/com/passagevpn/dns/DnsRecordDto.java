package com.passagevpn.dns;

import com.passagevpn.dns.DnsRecord.Scope;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

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
    @Pattern(
            regexp =
                "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|"
                    + "^([0-9a-fA-F]{1,4}:){1,7}:$|"
                    + "^::([0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}$|"
                    + "^([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}$|"
                    + "^::1$",
            message = "ipv6 must be a valid IPv6 address like fd00::1")
        String ipv6,
    @NotNull Scope scope,
    String scopeId,
    String scopeName,
    Boolean enabled,
    String createdAt,
    List<String> warnings) {

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
    return from(record, scopeName, List.of());
  }

  public static DnsRecordDto from(DnsRecord record, String scopeName, List<String> warnings) {
    return new DnsRecordDto(
        record.getId(),
        record.getHostname(),
        record.getIpv4(),
        record.getIpv6(),
        record.getScope(),
        record.getScopeId(),
        scopeName,
        record.isEnabled(),
        record.getCreatedAt() == null ? null : record.getCreatedAt().toString(),
        warnings == null ? List.of() : warnings);
  }
}
