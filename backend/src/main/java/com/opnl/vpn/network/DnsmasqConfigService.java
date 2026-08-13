package com.opnl.vpn.network;

import com.opnl.vpn.access.AccessRule;
import com.opnl.vpn.access.AccessRuleRepository;
import com.opnl.vpn.access.DomainResolver;
import com.opnl.vpn.config.OpnlProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/**
 * Renders the dnsmasq domain-pinning config into the shared config volume ({@code
 * <configDir>/dnsmasq.d/opnl-domains.conf}) from all enabled access rules that target a domain. The
 * openvpn container's dnsmasq loads the directory, so clients resolve pinned domains to exactly the
 * addresses the per-client firewall rules match.
 *
 * <p>Refreshed on application startup and after every access-rule mutation (see {@link
 * com.opnl.vpn.access.AccessRuleService}). Best-effort: resolution or write failures are logged and
 * never fail the calling mutation.
 */
@Slf4j
@Service
public class DnsmasqConfigService implements ApplicationRunner {

  public static final String DOMAINS_FILE_NAME = "opnl-domains.conf";
  public static final String DOMAINS_DIR_NAME = "dnsmasq.d";

  private final Path configDir;
  private final AccessRuleRepository ruleRepository;
  private final DomainResolver domainResolver;
  private final ServerConfigGenerator configGenerator;

  public DnsmasqConfigService(
      OpnlProperties properties,
      AccessRuleRepository ruleRepository,
      DomainResolver domainResolver,
      ServerConfigGenerator configGenerator) {
    this.configDir = Path.of(properties.openvpn().configDir()).toAbsolutePath();
    this.ruleRepository = ruleRepository;
    this.domainResolver = domainResolver;
    this.configGenerator = configGenerator;
  }

  @Override
  public void run(ApplicationArguments args) {
    refresh();
  }

  /** Re-renders {@code opnl-domains.conf} from the current enabled domain rules. */
  public synchronized void refresh() {
    Set<String> domains = new LinkedHashSet<>();
    for (AccessRule rule : ruleRepository.findByEnabledTrueAndDstDomainIsNotNull()) {
      if (rule.isEnabled() && rule.getDstDomain() != null && !rule.getDstDomain().isBlank()) {
        domains.add(rule.getDstDomain());
      }
    }
    write(domainResolver.resolveAll(domains));
  }

  private void write(Map<String, Set<String>> resolved) {
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
}
