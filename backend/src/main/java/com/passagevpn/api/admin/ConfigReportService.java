package com.passagevpn.api.admin;

import com.passagevpn.config.PassageProperties;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.network.DaemonService;
import com.passagevpn.pki.CertService;
import com.passagevpn.pki.Certificate;
import com.passagevpn.pki.CertificateRepository;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the admin configuration report: settings snapshot, daemon list, PKI inventory, versions.
 */
@Service
public class ConfigReportService {

  private static final String FALLBACK_VERSION = "0.1.0-SNAPSHOT";

  private final SettingsService settingsService;
  private final DaemonService daemonService;
  private final CertificateRepository certificateRepository;
  private final CertService certService;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final PassageProperties properties;
  private final Environment environment;

  public ConfigReportService(
      SettingsService settingsService,
      DaemonService daemonService,
      CertificateRepository certificateRepository,
      CertService certService,
      UserRepository userRepository,
      GroupRepository groupRepository,
      PassageProperties properties,
      Environment environment) {
    this.settingsService = settingsService;
    this.daemonService = daemonService;
    this.certificateRepository = certificateRepository;
    this.certService = certService;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.properties = properties;
    this.environment = environment;
  }

  @Transactional(readOnly = true)
  public ConfigReportDto report() {
    String dbUrl = environment.getProperty("spring.datasource.url", "");
    String dbType = dbUrl.startsWith("jdbc:sqlite:") ? "sqlite" : "postgresql";
    List<ConfigReportDto.DaemonSummary> daemons =
        daemonService.list().stream()
            .map(
                d ->
                    new ConfigReportDto.DaemonSummary(
                        d.getDaemonIndex(),
                        d.getName(),
                        d.getPort(),
                        d.getProto().name().toLowerCase(),
                        d.isEnabled()))
            .toList();
    ConfigReportDto.PkiInventory pki =
        new ConfigReportDto.PkiInventory(
            certificateRepository.count(),
            certificateRepository.countByStatus(Certificate.Status.VALID),
            certificateRepository.countByStatus(Certificate.Status.REVOKED),
            certificateRepository.countByStatus(Certificate.Status.EXPIRED),
            certService.expiringSoon().size());
    return new ConfigReportDto(
        properties.brandName(),
        version(),
        Instant.now().toString(),
        dbType,
        new ConfigReportDto.DataDirs(
            properties.openvpn().pkiDir(),
            properties.openvpn().ccdDir(),
            properties.openvpn().configDir(),
            properties.openvpn().logDir()),
        settingsService.serverSettings(),
        daemons,
        pki,
        userRepository.count(),
        groupRepository.count());
  }

  private String version() {
    String version = getClass().getPackage().getImplementationVersion();
    return version != null ? version : FALLBACK_VERSION;
  }
}
