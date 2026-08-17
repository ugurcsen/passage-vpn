package com.passagevpn.api.admin;

import com.passagevpn.config.PassageProperties;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.monitor.TrafficAggregator;
import com.passagevpn.network.ConnectionRegistry;
import com.passagevpn.network.ConnectionRegistry.VpnSession;
import com.passagevpn.network.Daemon;
import com.passagevpn.network.DaemonService;
import com.passagevpn.pki.Certificate;
import com.passagevpn.pki.CertificateRepository;
import com.passagevpn.user.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Aggregates dashboard counters and recent connection activity. */
@Service
public class DashboardAdminService {

  private static final int RECENT_CONNECTIONS_LIMIT = 10;
  private static final long COUNTS_TTL_MILLIS = 2_000;

  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final CertificateRepository certificateRepository;
  private final ConnectionRegistry connectionRegistry;
  private final DaemonService daemonService;
  private final TrafficAggregator trafficAggregator;
  private final PassageProperties properties;

  /** Cached DB counts (user/group/certificate), refreshed on a short TTL. */
  private volatile Counts cachedCounts;

  private volatile long countsLoadedAtMillis;

  public DashboardAdminService(
      UserRepository userRepository,
      GroupRepository groupRepository,
      CertificateRepository certificateRepository,
      ConnectionRegistry connectionRegistry,
      DaemonService daemonService,
      TrafficAggregator trafficAggregator,
      PassageProperties properties) {
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.certificateRepository = certificateRepository;
    this.connectionRegistry = connectionRegistry;
    this.daemonService = daemonService;
    this.trafficAggregator = trafficAggregator;
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
            .map(this::withTraffic)
            .toList();
    Counts counts = counts();
    return new DashboardDto(
        counts.users(),
        counts.groups(),
        counts.validCertificates(),
        sessions.size(),
        running,
        daemons.size(),
        recent);
  }

  /** DB counters are cheap to read but hit the DB; cache them for a couple of seconds. */
  private Counts counts() {
    long now = System.currentTimeMillis();
    Counts cached = cachedCounts;
    if (cached == null || now - countsLoadedAtMillis >= COUNTS_TTL_MILLIS) {
      cached =
          new Counts(
              userRepository.count(),
              groupRepository.count(),
              certificateRepository.countByStatus(Certificate.Status.VALID));
      cachedCounts = cached;
      countsLoadedAtMillis = now;
    }
    return cached;
  }

  /** Immutable snapshot of the rarely-changing dashboard counters. */
  private record Counts(long users, long groups, long validCertificates) {}

  /** Merges the session with its last known management-interface traffic counters. */
  private ConnectionDto withTraffic(VpnSession session) {
    TrafficAggregator.SessionTraffic traffic =
        trafficAggregator.trafficFor(session.commonName()).orElse(null);
    if (traffic == null) {
      return ConnectionDto.from(session);
    }
    return ConnectionDto.from(
        session,
        traffic.bytesIn(),
        traffic.bytesOut(),
        traffic.bytesInPerSec(),
        traffic.bytesOutPerSec());
  }
}
