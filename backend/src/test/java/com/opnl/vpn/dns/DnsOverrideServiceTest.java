package com.opnl.vpn.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.dns.DnsRecord.Scope;
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

class DnsOverrideServiceTest {

  private DnsRecordRepository recordRepository;
  private UserRepository userRepository;
  private GroupRepository groupRepository;
  private DnsmasqConfigService dnsmasqConfigService;
  private DnsOverrideService service;

  @BeforeEach
  void setUp() {
    recordRepository = mock(DnsRecordRepository.class);
    userRepository = mock(UserRepository.class);
    groupRepository = mock(GroupRepository.class);
    dnsmasqConfigService = mock(DnsmasqConfigService.class);
    service =
        new DnsOverrideService(
            recordRepository,
            userRepository,
            groupRepository,
            mock(AuditLogService.class),
            dnsmasqConfigService,
            mock(DnsScopeConflictService.class));
    when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private DnsRecordDto dto(String hostname, String ipv4, Scope scope, String scopeId) {
    return new DnsRecordDto(null, hostname, ipv4, null, scope, scopeId, null, true, null, null);
  }

  @Test
  void createNormalizesHostnameAndRefreshesDnsmasq() {
    when(recordRepository.findByHostnameIgnoreCase("git.internal")).thenReturn(Optional.empty());
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(User.builder().id("u1").username("bob").build()));

    DnsRecordDto result = service.create(dto("  Git.Internal ", "10.10.0.5", Scope.USER, "u1"));

    ArgumentCaptor<DnsRecord> captor = ArgumentCaptor.forClass(DnsRecord.class);
    verify(recordRepository).save(captor.capture());
    assertThat(captor.getValue().getHostname()).isEqualTo("git.internal");
    assertThat(captor.getValue().getIpv4()).isEqualTo("10.10.0.5");
    assertThat(captor.getValue().getScope()).isEqualTo(Scope.USER);
    assertThat(captor.getValue().getScopeId()).isEqualTo("u1");
    assertThat(result.hostname()).isEqualTo("git.internal");
    verify(dnsmasqConfigService).refresh();
  }

  @Test
  void createRejectsDuplicateHostname() {
    when(recordRepository.findByHostnameIgnoreCase("git.internal"))
        .thenReturn(Optional.of(record("d1", "git.internal", "10.10.0.5", Scope.GLOBAL, null)));

    assertThatThrownBy(() -> service.create(dto("git.internal", "10.10.0.6", Scope.GLOBAL, null)))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("dns_hostname_exists");
    verify(recordRepository, never()).save(any());
    verify(dnsmasqConfigService, never()).refresh();
  }

  @Test
  void createRejectsUnknownScopeTarget() {
    when(recordRepository.findByHostnameIgnoreCase("git.internal")).thenReturn(Optional.empty());
    when(userRepository.findById("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(dto("git.internal", "10.10.0.5", Scope.USER, "ghost")))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("scope_target_not_found");
  }

  @Test
  void updateAllowsKeepingOwnHostnameAndClearsScopeIdForGlobal() {
    DnsRecord existing = record("d1", "git.internal", "10.10.0.5", Scope.USER, "u1");
    when(recordRepository.findById("d1")).thenReturn(Optional.of(existing));
    when(recordRepository.findByHostnameIgnoreCase("git.internal"))
        .thenReturn(Optional.of(existing));

    DnsRecordDto result =
        service.update("d1", dto("git.internal", "10.10.0.9", Scope.GLOBAL, null));

    assertThat(result.ipv4()).isEqualTo("10.10.0.9");
    assertThat(existing.getScopeId()).isNull();
    verify(dnsmasqConfigService).refresh();
  }

  @Test
  void deleteRemovesRecordAndRefreshesDnsmasq() {
    when(recordRepository.findById("d1"))
        .thenReturn(Optional.of(record("d1", "git.internal", "10.10.0.5", Scope.GLOBAL, null)));

    service.delete("d1");

    verify(recordRepository).deleteById("d1");
    verify(dnsmasqConfigService).refresh();
  }

  @Test
  void deleteThrowsWhenRecordMissing() {
    when(recordRepository.findById("d1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete("d1"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("dns_record_not_found");
    verify(recordRepository, never()).deleteById(any());
  }

  @Test
  void resolveDomainReturnsOnlyEnabledMatchesIgnoringCase() {
    when(recordRepository.findByEnabledTrue())
        .thenReturn(
            List.of(
                record("d1", "git.internal", "10.10.0.5", Scope.GLOBAL, null),
                record("d2", "nas.internal", "10.10.0.9", Scope.GLOBAL, null),
                record("d3", "off.internal", "10.10.0.3", Scope.GLOBAL, null)));

    Set<String> ips = service.resolveDomain("  GIT.INTERNAL ");

    assertThat(ips).containsExactly("10.10.0.5");
  }

  @Test
  void resolveDomainReturnsEmptyForBlankOrUnknown() {
    assertThat(service.resolveDomain("  ")).isEmpty();
    assertThat(service.resolveDomain(null)).isEmpty();
  }

  @Test
  void nonGlobalEnabledFiltersOutGlobalRecords() {
    when(recordRepository.findByEnabledTrue())
        .thenReturn(
            List.of(
                record("d1", "git.internal", "10.10.0.5", Scope.GLOBAL, null),
                record("d2", "db.internal", "10.10.0.6", Scope.GROUP, "g1"),
                record("d3", "mon.internal", "10.10.0.7", Scope.USER, "u1"),
                record("d4", "off.internal", "10.10.0.3", Scope.GLOBAL, null)));

    List<DnsRecord> scoped = service.nonGlobalEnabled();

    assertThat(scoped)
        .extracting(DnsRecord::getHostname)
        .containsExactly("db.internal", "mon.internal");
  }

  @Test
  void listSortsByHostnameAndResolvesScopeNames() {
    when(recordRepository.findAll())
        .thenReturn(
            List.of(
                record("d1", "nas.internal", "10.10.0.9", Scope.USER, "u1"),
                record("d2", "git.internal", "10.10.0.5", Scope.GROUP, "g1"),
                record("d3", "db.internal", "10.10.0.6", Scope.GLOBAL, null)));
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(User.builder().id("u1").username("bob").build()));
    when(groupRepository.findById("g1"))
        .thenReturn(Optional.of(Group.builder().id("g1").name("devs").build()));

    List<DnsRecordDto> result = service.list();

    assertThat(result)
        .extracting(DnsRecordDto::hostname)
        .containsExactly("db.internal", "git.internal", "nas.internal");
    assertThat(result.get(1).scopeName()).isEqualTo("devs");
    assertThat(result.get(2).scopeName()).isEqualTo("bob");
    assertThat(result.get(0).scopeName()).isNull();
  }

  private DnsRecord record(String id, String hostname, String ipv4, Scope scope, String scopeId) {
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
}
