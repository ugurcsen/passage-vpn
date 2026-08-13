package com.opnl.vpn.network;

import com.opnl.vpn.access.AccessRule;
import com.opnl.vpn.access.AccessRuleRepository;
import com.opnl.vpn.access.DomainResolver;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.dns.DnsRecord;
import com.opnl.vpn.dns.DnsRecordRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/**
 * Renders the dnsmasq DNS configs into the shared config volume from the current access rules and
 * DNS overrides:
 *
 * <ul>
 *   <li>{@code <configDir>/dnsmasq.d/opnl-domains.conf} — pinning for access-rule domains (internal
 *       override answers win over public DNS resolution).
 *   <li>{@code <configDir>/dnsmasq.d/opnl-dns-overrides.conf} — admin-defined internal hostname →
 *       IPv4 records served authoritatively to every VPN client.
 * </ul>
 *
 * The openvpn container's dnsmasq loads the whole directory, so clients resolve pinned domains to
 * exactly the addresses the per-client firewall rules match.
 *
 * <p>Refreshed on application startup and after access-rule or DNS-override mutations (see {@link
 * com.opnl.vpn.access.AccessRuleService} and {@link com.opnl.vpn.dns.DnsOverrideService}).
 * Best-effort: resolution or write failures are logged and never fail the calling mutation.
 */
@Slf4j
@Service
public class DnsmasqConfigService implements ApplicationRunner {

  public static final String DOMAINS_FILE_NAME = "opnl-domains.conf";
  public static final String OVERRIDES_FILE_NAME = "opnl-dns-overrides.conf";
  public static final String DOMAINS_DIR_NAME = "dnsmasq.d";

  private final Path configDir;
  private final AccessRuleRepository ruleRepository;
  private final DnsRecordRepository recordRepository;
  private final DomainResolver domainResolver;
  private final ServerConfigGenerator configGenerator;

  public DnsmasqConfigService(
      OpnlProperties properties,
      AccessRuleRepository ruleRepository,
      DnsRecordRepository recordRepository,
      DomainResolver domainResolver,
      ServerConfigGenerator configGenerator) {
    this.configDir = Path.of(properties.openvpn().configDir()).toAbsolutePath();
    this.ruleRepository = ruleRepository;
    this.recordRepository = recordRepository;
    this.domainResolver = domainResolver;
    this.configGenerator = configGenerator;
  }

  @Override
  public void run(ApplicationArguments args) {
    refresh();
  }

  /**
   * Re-renders {@code opnl-domains.conf} and {@code opnl-dns-overrides.conf} from the current
   * enabled domain rules and DNS overrides. Access-rule domains that match an override resolve to
   * the override address instead of public DNS, so the dnsmasq answer and the firewall rules agree.
   */
  public synchronized void refresh() {
    Set<String> domains = new LinkedHashSet<>();
    for (AccessRule rule : ruleRepository.findByEnabledTrueAndDstDomainIsNotNull()) {
      if (rule.isEnabled() && rule.getDstDomain() != null && !rule.getDstDomain().isBlank()) {
        domains.add(rule.getDstDomain());
      }
    }
    writeDomains(domains);
    writeOverrides(recordRepository.findByEnabledTrue());
  }

  private void writeDomains(Set<String> domains) {
    Map<String, Set<String>> resolved = new LinkedHashMap<>();
    for (String domain : domains) {
      Set<String> overrideIps = overrideIps(domain);
      resolved.put(domain, overrideIps.isEmpty() ? domainResolver.resolve(domain) : overrideIps);
    }
    Path dir = configDir.resolve(DOMAINS_DIR_NAME);
    try {
      Files.createDirectories(dir);
      String content = configGenerator.renderDnsmasqConfig(resolved);
      Path file = dir.resolve(DOMAINS_FILE_NAME);
      Files.writeString(file, content, StandardCharsets.UTF_8);
      log.info(
          "Wrote {} ({} domains, {} addresses)",
          file,
          resolved.size(),
          resolved.values().stream().mapToInt(Set::size).sum());
    } catch (IOException e) {
      log.warn("Cannot write dnsmasq config: {}", e.getMessage());
    }
  }

  private void writeOverrides(List<DnsRecord> records) {
    Path dir = configDir.resolve(DOMAINS_DIR_NAME);
    try {
      Files.createDirectories(dir);
      List<DnsRecord> enabled = records.stream().filter(DnsRecord::isEnabled).toList();
      String content = configGenerator.renderDnsOverridesConfig(enabled);
      Path file = dir.resolve(OVERRIDES_FILE_NAME);
      Files.writeString(file, content, StandardCharsets.UTF_8);
      log.info("Wrote {} ({} records)", file, enabled.size());
    } catch (IOException e) {
      log.warn("Cannot write dnsmasq overrides config: {}", e.getMessage());
    }
  }

  private Set<String> overrideIps(String hostname) {
    Set<String> ips = new LinkedHashSet<>();
    for (DnsRecord record : recordRepository.findByEnabledTrue()) {
      if (record.getHostname().equalsIgnoreCase(hostname)) {
        ips.add(record.getIpv4());
      }
    }
    return ips;
  }
}
