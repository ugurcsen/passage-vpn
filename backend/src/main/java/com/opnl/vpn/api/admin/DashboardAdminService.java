package com.opnl.vpn.api.admin;

import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.network.ConnectionRegistry;
import com.opnl.vpn.network.ConnectionRegistry.VpnSession;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.pki.Certificate;
import com.opnl.vpn.pki.CertificateRepository;
import com.opnl.vpn.user.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Aggregates dashboard counters and recent connection activity. */
@Service
public class DashboardAdminService {

  private static final int RECENT_CONNECTIONS_LIMIT = 10;

  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final CertificateRepository certificateRepository;
  private final ConnectionRegistry connectionRegistry;
  private final DaemonService daemonService;
  private final OpnlProperties properties;

  public DashboardAdminService(
      UserRepository userRepository,
      GroupRepository groupRepository,
      CertificateRepository certificateRepository,
      ConnectionRegistry connectionRegistry,
      DaemonService daemonService,
      OpnlProperties properties) {
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.certificateRepository = certificateRepository;
    this.connectionRegistry = connectionRegistry;
    this.daemonService = daemonService;
    this.properties = properties;
  }

  public DashboardDto dashboard() {
    List<VpnSession> sessions = connectionRegistry.sessions();
    List<Daemon> daemons = daemonService.list();
    Path configDir = Path.of(properties.openvpn().configDir());
    long running =
        daemons.stream()
            .filter(Daemon::isEnabled)
            .filter(d -> Files.exists(configDir.resolve("daemon-" + d.getDaemonIndex() + ".conf")))
            .count();
    List<ConnectionDto> recent =
        sessions.stream()
            .sorted(Comparator.comparing(VpnSession::connectedAt).reversed())
            .limit(RECENT_CONNECTIONS_LIMIT)
            .map(ConnectionDto::from)
            .toList();
    return new DashboardDto(
        userRepository.count(),
        groupRepository.count(),
        certificateRepository.countByStatus(Certificate.Status.VALID),
        sessions.size(),
        running,
        daemons.size(),
        recent);
  }
}
