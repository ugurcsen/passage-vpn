package com.passagevpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.passagevpn.config.PassageProperties;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.network.DaemonService;
import com.passagevpn.network.ServerConfig.Protocol;
import com.passagevpn.pki.CertService;
import com.passagevpn.pki.Certificate;
import com.passagevpn.pki.CertificateRepository;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Unit tests for the configuration report builder. */
class ConfigReportServiceTest {

  private SettingsService settingsService;
  private DaemonService daemonService;
  private CertificateRepository certificateRepository;
  private CertService certService;
  private UserRepository userRepository;
  private GroupRepository groupRepository;
  private ConfigReportService reportService;

  @BeforeEach
  void setUp() {
    settingsService = mock(SettingsService.class);
    daemonService = mock(DaemonService.class);
    certificateRepository = mock(CertificateRepository.class);
    certService = mock(CertService.class);
    userRepository = mock(UserRepository.class);
    groupRepository = mock(GroupRepository.class);
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.datasource.url", "jdbc:sqlite:./data/passage.db");
    reportService =
        new ConfigReportService(
            settingsService,
            daemonService,
            certificateRepository,
            certService,
            userRepository,
            groupRepository,
            new PassageProperties(
                "./data",
                "OpenVPN Panel",
                "secret",
                null,
                null,
                new PassageProperties.OpenVpn(
                    "openvpn",
                    7505,
                    "localhost",
                    "./data/pki",
                    "./data/ccd",
                    "./data/config",
                    "./data/config/scripts",
                    "./openvpn/scripts",
                    "http://backend:8080",
                    "easyrsa",
                    "./data/config/logs",
                    "mgmt-pass",
                    730,
                    1194,
                    1194,
                    1195,
                    1195)),
            environment);
  }

  @Test
  void reportsSettingsDaemonsPkiAndCounts() {
    when(settingsService.serverSettings()).thenReturn(Map.of("network_mode", "nat"));
    when(daemonService.list())
        .thenReturn(
            List.of(
                daemon(0, "Primary", 1194, Protocol.udp, true),
                daemon(1, "Generic", 1195, Protocol.tcp, false)));
    when(certificateRepository.count()).thenReturn(5L);
    when(certificateRepository.countByStatus(Certificate.Status.VALID)).thenReturn(3L);
    when(certificateRepository.countByStatus(Certificate.Status.REVOKED)).thenReturn(1L);
    when(certificateRepository.countByStatus(Certificate.Status.EXPIRED)).thenReturn(1L);
    when(certService.expiringSoon()).thenReturn(List.of());
    when(userRepository.count()).thenReturn(12L);
    when(groupRepository.count()).thenReturn(2L);

    ConfigReportDto report = reportService.report();

    assertThat(report.brand()).isEqualTo("OpenVPN Panel");
    assertThat(report.dbType()).isEqualTo("sqlite");
    assertThat(report.serverSettings()).containsEntry("network_mode", "nat");
    assertThat(report.daemons()).hasSize(2);
    assertThat(report.daemons().get(0).name()).isEqualTo("Primary");
    assertThat(report.daemons().get(1).enabled()).isFalse();
    assertThat(report.pki().total()).isEqualTo(5);
    assertThat(report.pki().valid()).isEqualTo(3);
    assertThat(report.pki().revoked()).isEqualTo(1);
    assertThat(report.pki().expired()).isEqualTo(1);
    assertThat(report.users()).isEqualTo(12);
    assertThat(report.groups()).isEqualTo(2);
    assertThat(report.dataDirs().pki()).isEqualTo("./data/pki");
    assertThat(report.generatedAt()).isNotBlank();
  }

  private static com.passagevpn.network.Daemon daemon(
      int index, String name, int port, Protocol proto, boolean enabled) {
    return com.passagevpn.network.Daemon.builder()
        .id("d" + index)
        .daemonIndex(index)
        .name(name)
        .port(port)
        .proto(proto)
        .subnet("10.8.0.0")
        .subnetMask("255.255.255.0")
        .dnsServers(List.of())
        .extraRoutes(List.of())
        .fullTunnel(true)
        .clientCertNotRequired(false)
        .authUserPass(true)
        .enabled(enabled)
        .build();
  }
}
