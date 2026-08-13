package com.opnl.vpn.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.access.AccessRule;
import com.opnl.vpn.access.AccessRule.TargetType;
import com.opnl.vpn.access.AccessRuleRepository;
import com.opnl.vpn.access.DomainResolver;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.dns.DnsRecord;
import com.opnl.vpn.dns.DnsRecordRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DnsmasqConfigServiceTest {

  @TempDir Path tempDir;

  private AccessRuleRepository ruleRepository;
  private DnsRecordRepository recordRepository;
  private DomainResolver domainResolver;
  private DnsmasqConfigService service;

  @BeforeEach
  void setUp() {
    ruleRepository = mock(AccessRuleRepository.class);
    recordRepository = mock(DnsRecordRepository.class);
    when(recordRepository.findByEnabledTrue()).thenReturn(List.of());
    domainResolver = mock(DomainResolver.class);
    service =
        new DnsmasqConfigService(
            new OpnlProperties(
                null,
                null,
                null,
                null,
                null,
                new OpnlProperties.OpenVpn(
                    null, 0, null, null, null, tempDir.toString(), null, null, null, null, null)),
            ruleRepository,
            recordRepository,
            domainResolver,
            new ServerConfigGenerator(new ObjectMapper()));
  }

  private AccessRule domainRule(String id, String domain, boolean enabled) {
    return AccessRule.builder()
        .id(id)
        .targetType(TargetType.GLOBAL)
        .action(AccessRule.Action.ALLOW)
        .dstDomain(domain)
        .enabled(enabled)
        .createdAt(Instant.now())
        .build();
  }

  @Test
  void refreshResolvesEnabledDomainsAndWritesConf() throws Exception {
    when(ruleRepository.findByEnabledTrueAndDstDomainIsNotNull())
        .thenReturn(
            List.of(
                domainRule("r1", "api.github.com", true),
                domainRule("r2", "disabled.example.com", false),
                domainRule("r3", "www.example.com", true)));
    when(domainResolver.resolve("api.github.com")).thenReturn(Set.of("140.82.112.5"));
    when(domainResolver.resolve("www.example.com")).thenReturn(Set.of("93.184.215.14"));

    service.refresh();

    String conf = Files.readString(tempDir.resolve("dnsmasq.d/opnl-domains.conf"));
    assertThat(conf)
        .contains("address=/api.github.com/140.82.112.5")
        .contains("server=/api.github.com/")
        .contains("address=/www.example.com/93.184.215.14")
        .contains("server=/www.example.com/")
        .doesNotContain("disabled.example.com");
  }

  @Test
  void refreshWithNoDomainRulesWritesEmptyConf() throws Exception {
    when(ruleRepository.findByEnabledTrueAndDstDomainIsNotNull()).thenReturn(List.of());

    service.refresh();

    String conf = Files.readString(tempDir.resolve("dnsmasq.d/opnl-domains.conf"));
    assertThat(conf).doesNotContain("address=/");
  }

  @Test
  void refreshWritesOverridesConfFromEnabledRecords() throws Exception {
    when(ruleRepository.findByEnabledTrueAndDstDomainIsNotNull()).thenReturn(List.of());
    when(recordRepository.findByEnabledTrue())
        .thenReturn(
            List.of(
                dnsRecord("d1", "git.internal", "10.10.0.5", true),
                dnsRecord("d2", "nas.internal", "10.10.0.9", false)));

    service.refresh();

    String conf = Files.readString(tempDir.resolve("dnsmasq.d/opnl-dns-overrides.conf"));
    assertThat(conf)
        .contains("address=/git.internal/10.10.0.5")
        .contains("server=/git.internal/")
        .doesNotContain("nas.internal");
  }

  @Test
  void refreshPrefersOverrideAddressOverPublicResolution() throws Exception {
    when(ruleRepository.findByEnabledTrueAndDstDomainIsNotNull())
        .thenReturn(List.of(domainRule("r1", "git.internal", true)));
    when(recordRepository.findByEnabledTrue())
        .thenReturn(List.of(dnsRecord("d1", "git.internal", "10.10.0.5", true)));
    when(domainResolver.resolve("git.internal")).thenReturn(Set.of("1.2.3.4"));

    service.refresh();

    String conf = Files.readString(tempDir.resolve("dnsmasq.d/opnl-domains.conf"));
    assertThat(conf).contains("address=/git.internal/10.10.0.5").doesNotContain("1.2.3.4");
  }

  private DnsRecord dnsRecord(String id, String hostname, String ipv4, boolean enabled) {
    return DnsRecord.builder()
        .id(id)
        .hostname(hostname)
        .ipv4(ipv4)
        .scope(DnsRecord.Scope.GLOBAL)
        .enabled(enabled)
        .createdAt(Instant.now())
        .build();
  }
}
