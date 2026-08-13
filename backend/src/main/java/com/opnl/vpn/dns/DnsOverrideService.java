package com.opnl.vpn.dns;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.network.DnsmasqConfigService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD for DNS overrides (internal hostname → IPv4 records served authoritatively by the VPN
 * dnsmasq). Every mutation refreshes the dnsmasq configs so changes reach clients immediately. The
 * engine reads {@link #resolveDomain} and {@link #nonGlobalEnabled} to resolve access-rule domains
 * and compute per-client scope denies.
 */
@Slf4j
@Service
public class DnsOverrideService {

  private final DnsRecordRepository recordRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final AuditLogService auditLogService;
  private final DnsmasqConfigService dnsmasqConfigService;

  public DnsOverrideService(
      DnsRecordRepository recordRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      AuditLogService auditLogService,
      DnsmasqConfigService dnsmasqConfigService) {
    this.recordRepository = recordRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.auditLogService = auditLogService;
    this.dnsmasqConfigService = dnsmasqConfigService;
  }

  @Transactional(readOnly = true)
  public List<DnsRecordDto> list() {
    return recordRepository.findAll().stream()
        .map(record -> DnsRecordDto.from(record, scopeName(record)))
        .sorted(java.util.Comparator.comparing(DnsRecordDto::hostname))
        .toList();
  }

  @Transactional
  public DnsRecordDto create(DnsRecordDto dto) {
    validate(dto, null);
    DnsRecord record = new DnsRecord();
    record.setId(UUID.randomUUID().toString());
    record.setCreatedAt(Instant.now());
    apply(record, dto);
    DnsRecord saved = recordRepository.save(record);
    auditLogService.record(
        "DNS_RECORD_CREATE",
        AuditLogService.CAT_DNS,
        saved.getId(),
        "dns_record",
        Map.of(
            "hostname",
            saved.getHostname(),
            "ipv4",
            saved.getIpv4(),
            "scope",
            saved.getScope().name()));
    refreshDnsmasq();
    return DnsRecordDto.from(saved, scopeName(saved));
  }

  @Transactional
  public DnsRecordDto update(String id, DnsRecordDto dto) {
    DnsRecord record = requireRecord(id);
    validate(dto, id);
    apply(record, dto);
    DnsRecord saved = recordRepository.save(record);
    auditLogService.record("DNS_RECORD_UPDATE", AuditLogService.CAT_DNS, id, "dns_record", null);
    refreshDnsmasq();
    return DnsRecordDto.from(saved, scopeName(saved));
  }

  @Transactional
  public void delete(String id) {
    requireRecord(id);
    recordRepository.deleteById(id);
    auditLogService.record("DNS_RECORD_DELETE", AuditLogService.CAT_DNS, id, "dns_record", null);
    refreshDnsmasq();
  }

  @Transactional
  public DnsRecordDto setEnabled(String id, boolean enabled) {
    DnsRecord record = requireRecord(id);
    record.setEnabled(enabled);
    auditLogService.record(
        enabled ? "DNS_RECORD_ENABLE" : "DNS_RECORD_DISABLE",
        AuditLogService.CAT_DNS,
        id,
        "dns_record",
        null);
    refreshDnsmasq();
    return DnsRecordDto.from(recordRepository.save(record), scopeName(record));
  }

  /**
   * Static IPv4 answers for a hostname from enabled overrides, or an empty set when no override
   * matches. The rule engine uses this before falling back to public DNS, so internal hostnames are
   * usable as access-rule destinations.
   */
  @Transactional(readOnly = true)
  public Set<String> resolveDomain(String hostname) {
    if (hostname == null || hostname.isBlank()) {
      return Set.of();
    }
    return recordRepository.findByEnabledTrue().stream()
        .filter(record -> record.getHostname().equalsIgnoreCase(hostname.trim()))
        .map(DnsRecord::getIpv4)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  /** Enabled records that are scoped to a group or user (never GLOBAL). */
  @Transactional(readOnly = true)
  public List<DnsRecord> nonGlobalEnabled() {
    return recordRepository.findByEnabledTrue().stream()
        .filter(record -> record.getScope() != DnsRecord.Scope.GLOBAL)
        .toList();
  }

  private void apply(DnsRecord record, DnsRecordDto dto) {
    record.setHostname(dto.hostname().trim().toLowerCase(Locale.ROOT));
    record.setIpv4(dto.ipv4().trim());
    record.setScope(dto.scope());
    record.setScopeId(dto.scope() == DnsRecord.Scope.GLOBAL ? null : dto.scopeId().trim());
    record.setEnabled(dto.enabled() == null || dto.enabled());
  }

  private void validate(DnsRecordDto dto, String currentId) {
    String hostname = dto.hostname() == null ? "" : dto.hostname().trim().toLowerCase(Locale.ROOT);
    if (hostname.isBlank()) {
      throw ApiException.badRequest("missing_hostname", "Hostname is required");
    }
    if (dto.ipv4() == null || dto.ipv4().isBlank()) {
      throw ApiException.badRequest("missing_ipv4", "IPv4 address is required");
    }
    recordRepository
        .findByHostnameIgnoreCase(hostname)
        .ifPresent(
            existing -> {
              if (!existing.getId().equals(currentId)) {
                throw ApiException.conflict(
                    "dns_hostname_exists", "A DNS override for " + hostname + " already exists");
              }
            });
    if (dto.scope() == DnsRecord.Scope.GROUP || dto.scope() == DnsRecord.Scope.USER) {
      if (dto.scopeId() == null || dto.scopeId().isBlank()) {
        throw ApiException.badRequest("missing_scope_id", "scopeId is required for scoped records");
      }
      boolean targetExists =
          dto.scope() == DnsRecord.Scope.GROUP
              ? groupRepository.findById(dto.scopeId()).isPresent()
              : userRepository.findById(dto.scopeId()).isPresent();
      if (!targetExists) {
        throw ApiException.notFound(
            "scope_target_not_found",
            "Target " + (dto.scope() == DnsRecord.Scope.GROUP ? "group" : "user") + " not found");
      }
    }
  }

  private DnsRecord requireRecord(String id) {
    return recordRepository
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("dns_record_not_found", "DNS record not found"));
  }

  private String scopeName(DnsRecord record) {
    if (record.getScope() == DnsRecord.Scope.USER) {
      return userRepository.findById(record.getScopeId()).map(User::getUsername).orElse(null);
    }
    if (record.getScope() == DnsRecord.Scope.GROUP) {
      return groupRepository.findById(record.getScopeId()).map(Group::getName).orElse(null);
    }
    return null;
  }

  private void refreshDnsmasq() {
    try {
      dnsmasqConfigService.refresh();
    } catch (RuntimeException e) {
      // A dnsmasq render/write failure must never break DNS record CRUD.
      log.warn("Cannot refresh dnsmasq config after DNS override mutation: {}", e.getMessage());
    }
  }
}
