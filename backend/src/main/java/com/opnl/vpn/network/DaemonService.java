package com.opnl.vpn.network;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.ServerConfig.Protocol;
import com.opnl.vpn.profile.ProfileType;
import com.opnl.vpn.setting.SettingKeys;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the {@code daemons} table and maps connection profile types onto a serving daemon.
 *
 * <p>Daemon index 0 is the primary daemon created by the setup wizard. Each profile type is served
 * by a daemon whose flags match it:
 *
 * <ul>
 *   <li>GENERIC &rarr; a {@code client-cert-not-required} daemon
 *   <li>AUTO_LOGIN &rarr; a cert-only daemon (no auth-user-pass, certs required)
 *   <li>USER_LOCKED / SERVER_LOCKED &rarr; a password daemon (auth-user-pass + certs required)
 * </ul>
 *
 * <p>When no enabled daemon matches, the primary daemon is used as a fallback so single-daemon
 * installs keep working.
 */
@Slf4j
@Service
public class DaemonService {

  private static final String NETWORK_SETTING_KEY = "network";
  private static final int DEFAULT_UDP_PORT = 1194;
  private static final int DEFAULT_TCP_PORT = 1195;

  private final DaemonRepository repository;
  private final ServerSettingRepository settingRepository;
  private final ServerConfigGenerator generator;
  private final ConfigWriter configWriter;
  private final OpnlProperties properties;
  private final AuditLogService auditLogService;
  private final NodeRegistryService nodeRegistryService;

  public DaemonService(
      DaemonRepository repository,
      ServerSettingRepository settingRepository,
      ServerConfigGenerator generator,
      ConfigWriter configWriter,
      OpnlProperties properties,
      AuditLogService auditLogService,
      NodeRegistryService nodeRegistryService) {
    this.repository = repository;
    this.settingRepository = settingRepository;
    this.generator = generator;
    this.configWriter = configWriter;
    this.properties = properties;
    this.auditLogService = auditLogService;
    this.nodeRegistryService = nodeRegistryService;
  }

  /**
   * Lists daemons in index order. Seeds the primary daemon from the legacy network setting when the
   * table is empty (fresh install or pre-multi-daemon upgrade).
   */
  @Transactional
  public List<Daemon> list() {
    if (repository.count() == 0) {
      ensurePrimary();
    }
    return repository.findAllByOrderByDaemonIndexAsc();
  }

  /** Ensures daemon index 0 exists, creating it from the stored network config if needed. */
  @Transactional
  public Daemon ensurePrimary() {
    return repository
        .findByDaemonIndex(0)
        .orElseGet(() -> repository.save(toEntity(legacyNetworkConfig(), 0)));
  }

  /**
   * Rewrites every local daemon config into the shared volume; disables remove their config file.
   * Daemons assigned to a remote node run on that gateway and never touch the local volume.
   */
  @Transactional
  public void writeAll() {
    ensurePrimary();
    String networkMode = networkMode();
    for (Daemon daemon : repository.findAllByOrderByDaemonIndexAsc()) {
      if (daemon.getNodeId() != null) {
        continue;
      }
      if (daemon.isEnabled()) {
        configWriter.writeDaemon(toServerConfig(daemon), generator, properties, networkMode);
      } else {
        configWriter.removeDaemon(daemon.getDaemonIndex());
      }
    }
  }

  @Transactional
  public Daemon create(DaemonRequest request) {
    int port = resolvePort(request, null);
    validateUnique(request, port, null);
    validateNode(request.nodeId());
    Daemon daemon =
        Daemon.builder()
            .id(UUID.randomUUID().toString())
            .daemonIndex(request.daemonIndex())
            .name(blankToNull(request.name()))
            .port(port)
            .proto(request.proto())
            .subnet(request.subnet())
            .subnetMask(request.subnetMask())
            .dnsServers(request.dnsServers() == null ? List.of() : request.dnsServers())
            .domain(blankToNull(request.domain()))
            .extraRoutes(request.extraRoutes() == null ? List.of() : request.extraRoutes())
            .fullTunnel(request.fullTunnel())
            .clientCertNotRequired(request.clientCertNotRequired())
            .authUserPass(request.authUserPass())
            .adminHost(blankToNull(request.adminHost()))
            .nodeId(blankToNull(request.nodeId()))
            .ipv6Enabled(request.ipv6Enabled())
            .ipv6Subnet(blankToNull(request.ipv6Subnet()))
            .enabled(request.enabled())
            .createdAt(Instant.now())
            .build();
    Daemon saved = repository.save(daemon);
    writeAll();
    log.info("Created daemon {} '{}'", saved.getDaemonIndex(), saved.getName());
    auditLogService.record(
        "DAEMON_CREATE",
        AuditLogService.CAT_DAEMON,
        saved.getId(),
        "daemon",
        Map.of("index", saved.getDaemonIndex(), "name", String.valueOf(saved.getName())));
    return saved;
  }

  @Transactional
  public Daemon update(String id, DaemonRequest request) {
    Daemon daemon = require(id);
    int port = resolvePort(request, id);
    validateUnique(request, port, id);
    validateNode(request.nodeId());
    daemon.setDaemonIndex(request.daemonIndex());
    daemon.setName(blankToNull(request.name()));
    daemon.setPort(port);
    daemon.setProto(request.proto());
    daemon.setSubnet(request.subnet());
    daemon.setSubnetMask(request.subnetMask());
    daemon.setDnsServers(request.dnsServers() == null ? List.of() : request.dnsServers());
    daemon.setDomain(blankToNull(request.domain()));
    daemon.setExtraRoutes(request.extraRoutes() == null ? List.of() : request.extraRoutes());
    daemon.setFullTunnel(request.fullTunnel());
    daemon.setClientCertNotRequired(request.clientCertNotRequired());
    daemon.setAuthUserPass(request.authUserPass());
    daemon.setAdminHost(blankToNull(request.adminHost()));
    daemon.setNodeId(blankToNull(request.nodeId()));
    daemon.setIpv6Enabled(request.ipv6Enabled());
    daemon.setIpv6Subnet(blankToNull(request.ipv6Subnet()));
    daemon.setEnabled(request.enabled());
    Daemon saved = repository.save(daemon);
    writeAll();
    log.info("Updated daemon {} '{}'", saved.getDaemonIndex(), saved.getName());
    auditLogService.record(
        "DAEMON_UPDATE",
        AuditLogService.CAT_DAEMON,
        saved.getId(),
        "daemon",
        Map.of("index", saved.getDaemonIndex(), "name", String.valueOf(saved.getName())));
    return saved;
  }

  /** Deletes a non-primary daemon and removes its config file. */
  @Transactional
  public void delete(String id) {
    Daemon daemon = require(id);
    if (daemon.getDaemonIndex() == 0) {
      throw ApiException.badRequest("primary_daemon", "The primary daemon cannot be deleted");
    }
    repository.delete(daemon);
    if (daemon.getNodeId() == null) {
      configWriter.removeDaemon(daemon.getDaemonIndex());
    }
    log.info("Deleted daemon {} '{}'", daemon.getDaemonIndex(), daemon.getName());
    auditLogService.record(
        "DAEMON_DELETE",
        AuditLogService.CAT_DAEMON,
        daemon.getId(),
        "daemon",
        Map.of("index", daemon.getDaemonIndex(), "name", String.valueOf(daemon.getName())));
  }

  @Transactional
  public Daemon setEnabled(String id, boolean enabled) {
    Daemon daemon = require(id);
    daemon.setEnabled(enabled);
    Daemon saved = repository.save(daemon);
    writeAll();
    auditLogService.record(
        enabled ? "DAEMON_ENABLE" : "DAEMON_DISABLE",
        AuditLogService.CAT_DAEMON,
        saved.getId(),
        "daemon",
        Map.of("index", saved.getDaemonIndex(), "name", String.valueOf(saved.getName())));
    return saved;
  }

  /** Resolves the daemon config that serves the given profile type (see class javadoc). */
  @Transactional
  public ServerConfig resolveForProfile(ProfileType type) {
    return toServerConfig(entityForProfile(type));
  }

  /**
   * Resolves the endpoint of a single pinned daemon for the given profile type, validating that the
   * daemon exists, is enabled, serves the type and runs on a serving node. Used by per-daemon
   * profile downloads (full-tunnel vs split-tunnel daemons) so clients connect to exactly the
   * chosen instance instead of load-balancing across all matching daemons.
   */
  @Transactional(readOnly = true)
  public ProfileEndpoint resolvePinnedForProfile(ProfileType type, int daemonIndex) {
    Daemon daemon =
        repository
            .findByDaemonIndex(daemonIndex)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "daemon_not_found", "Daemon " + daemonIndex + " not found"));
    if (!servesProfile(daemon, type)) {
      throw ApiException.badRequest(
          "daemon_mismatch", "Daemon " + daemonIndex + " does not serve profile type " + type);
    }
    if (!nodeServing(daemon)) {
      throw ApiException.badRequest(
          "daemon_mismatch", "Daemon " + daemonIndex + " is not currently reachable");
    }
    return new ProfileEndpoint(
        toServerConfig(daemon), effectiveAdminHost(daemon), daemon.getName());
  }

  /**
   * Every daemon that serves the given profile type, local and remote, in daemon-index order. Used
   * to embed multiple {@code remote} endpoints in a connection profile; falls back to the primary
   * daemon when nothing matches so single-daemon installs keep working.
   */
  @Transactional(readOnly = true)
  public List<ProfileEndpoint> resolveAllForProfile(ProfileType type) {
    List<Daemon> daemons = findAllForProfile(type);
    return daemons.stream()
        .map(d -> new ProfileEndpoint(toServerConfig(d), effectiveAdminHost(d)))
        .toList();
  }

  /** Whether the given daemon runs a dual-stack tunnel. */
  @Transactional(readOnly = true)
  public boolean ipv6Enabled(int daemonIndex) {
    return repository.findByDaemonIndex(daemonIndex).map(Daemon::isIpv6Enabled).orElse(false);
  }

  /**
   * The enabled daemons assigned to a remote node (nodeId match), in daemon-index order. Used to
   * assemble the config bundle a node agent pulls to run its gateways.
   */
  @Transactional(readOnly = true)
  public List<Daemon> enabledDaemonsForNode(String nodeId) {
    return repository.findByEnabledTrueOrderByDaemonIndexAsc().stream()
        .filter(d -> nodeId != null && nodeId.equals(d.getNodeId()))
        .toList();
  }

  /** Whether the primary daemon runs a dual-stack tunnel (default for panel-wide features). */
  @Transactional(readOnly = true)
  public boolean primaryIpv6Enabled() {
    return ipv6Enabled(0);
  }

  /** Returns the daemon entity serving the given profile type, falling back to the primary. */
  @Transactional
  public Daemon entityForProfile(ProfileType type) {
    return findMatchingForProfile(type).orElseGet(() -> primary(list()));
  }

  /**
   * The first enabled daemon whose flags match the given profile type, without the primary
   * fallback. Used by the portal to hide profile types that no daemon can actually serve.
   */
  @Transactional(readOnly = true)
  public Optional<Daemon> findMatchingForProfile(ProfileType type) {
    return repository.findByEnabledTrueOrderByDaemonIndexAsc().stream()
        .filter(matchesProfile(type))
        .findFirst();
  }

  /**
   * All enabled daemons matching the profile type (on enabled, existing nodes), or the primary as
   * fallback.
   */
  @Transactional(readOnly = true)
  public List<Daemon> findAllForProfile(ProfileType type) {
    List<Daemon> matches =
        repository.findByEnabledTrueOrderByDaemonIndexAsc().stream()
            .filter(matchesProfile(type))
            .filter(this::nodeServing)
            .toList();
    if (!matches.isEmpty()) {
      return matches;
    }
    Daemon fallback = primary(list());
    return fallback == null ? List.of() : List.of(fallback);
  }

  /** A daemon serves traffic only when local or when its node exists and is enabled. */
  private boolean nodeServing(Daemon daemon) {
    if (daemon.getNodeId() == null) {
      return true;
    }
    return nodeRegistryService
        .findNode(daemon.getNodeId())
        .map(OpenVpnNode::isEnabled)
        .orElse(false);
  }

  /**
   * The effective public host for a daemon's profile endpoint: the daemon-level adminHost wins,
   * then the owning node's adminHost (remote nodes), then the global {@code OPNL_ADMIN_HOST}.
   * Returns null when none is set; callers fall back to a sensible default host.
   */
  public String effectiveAdminHost(Daemon daemon) {
    String host = blankToNull(daemon.getAdminHost());
    if (host == null && daemon.getNodeId() != null) {
      host =
          nodeRegistryService
              .findNode(daemon.getNodeId())
              .map(n -> blankToNull(n.getAdminHost()))
              .orElse(null);
    }
    if (host == null) {
      OpnlProperties.OpenVpn openvpn = properties.openvpn();
      if (openvpn != null) {
        host = blankToNull(openvpn.adminHost());
      }
    }
    return host;
  }

  private java.util.function.Predicate<Daemon> matchesProfile(ProfileType type) {
    return d -> servesProfile(d, type);
  }

  /** Whether the daemon's flag combination serves the given profile type (see class javadoc). */
  private boolean servesProfile(Daemon daemon, ProfileType type) {
    return switch (type) {
      case GENERIC -> daemon.isEnabled() && daemon.isClientCertNotRequired();
      case AUTO_LOGIN ->
          daemon.isEnabled() && !daemon.isClientCertNotRequired() && !daemon.isAuthUserPass();
      case USER_LOCKED, SERVER_LOCKED ->
          daemon.isEnabled() && !daemon.isClientCertNotRequired() && daemon.isAuthUserPass();
    };
  }

  /** A single profile-serving endpoint with the effective public host clients should connect to. */
  public record ProfileEndpoint(ServerConfig config, String host, String name) {
    public ProfileEndpoint(ServerConfig config, String host) {
      this(config, host, null);
    }
  }

  /** Maps an entity to the shared config shape used by generators and writers. */
  public ServerConfig toServerConfig(Daemon daemon) {
    return new ServerConfig(
        daemon.getDaemonIndex(),
        daemon.getPort(),
        daemon.getProto(),
        daemon.getSubnet(),
        daemon.getSubnetMask(),
        daemon.getDnsServers(),
        daemon.getDomain(),
        daemon.getExtraRoutes(),
        daemon.isFullTunnel(),
        daemon.isClientCertNotRequired(),
        daemon.isAuthUserPass(),
        daemon.getAdminHost(),
        daemon.isIpv6Enabled(),
        daemon.getIpv6Subnet());
  }

  private Daemon primary(List<Daemon> daemons) {
    return daemons.stream()
        .filter(d -> d.getDaemonIndex() == 0)
        .findFirst()
        .orElse(daemons.isEmpty() ? null : daemons.get(0));
  }

  private Daemon require(String id) {
    return repository
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("daemon_not_found", "Daemon not found"));
  }

  private void validateUnique(DaemonRequest request, int port, String excludedId) {
    repository
        .findByDaemonIndex(request.daemonIndex())
        .filter(d -> !d.getId().equals(excludedId))
        .ifPresent(
            d -> {
              throw ApiException.conflict(
                  "daemon_index_taken",
                  "Daemon index " + request.daemonIndex() + " is already in use");
            });
    repository.findAll().stream()
        .filter(d -> !d.getId().equals(excludedId))
        .filter(d -> d.getPort() == port)
        .findAny()
        .ifPresent(
            d -> {
              throw ApiException.conflict(
                  "daemon_port_taken", "Port " + port + " is already in use");
            });
    repository.findAll().stream()
        .filter(d -> !d.getId().equals(excludedId))
        .filter(d -> d.getSubnet().equals(request.subnet()))
        .findAny()
        .ifPresent(
            d -> {
              throw ApiException.conflict(
                  "daemon_subnet_taken", "Subnet " + request.subnet() + " is already in use");
            });
  }

  /**
   * Resolves the daemon's listen port. An explicit port must fall inside the host-published range
   * of its protocol family (local daemons only — anything else is unreachable from the host); an
   * empty port is auto-assigned the lowest free port of that range. Remote-node daemons are
   * provisioned by their own gateway and must always carry an explicit port.
   */
  private int resolvePort(DaemonRequest request, String excludedId) {
    if (request.nodeId() != null && !request.nodeId().isBlank()) {
      if (request.port() == null) {
        throw ApiException.badRequest(
            "daemon_port_required",
            "A remote-node daemon must specify an explicit port (published by its gateway)");
      }
      return request.port();
    }
    OpnlProperties.OpenVpn openvpn = properties.openvpn();
    int base;
    int end;
    if (isUdp(request.proto())) {
      base = openvpn != null ? openvpn.udpRangeStart() : DEFAULT_UDP_PORT;
      end = openvpn != null ? openvpn.udpRangeEnd() : DEFAULT_UDP_PORT;
    } else {
      base = openvpn != null ? openvpn.tcpRangeStart() : DEFAULT_TCP_PORT;
      end = openvpn != null ? openvpn.tcpRangeEnd() : DEFAULT_TCP_PORT;
    }
    if (request.port() != null) {
      if (request.port() < base || request.port() > end) {
        throw ApiException.badRequest(
            "daemon_port_not_published",
            "Port "
                + request.port()
                + " is outside the published "
                + request.proto()
                + " range "
                + base
                + "-"
                + end
                + "; extend OPNL_OPENVPN_PORT_END / OPNL_OPENVPN_TCP_PORT_END to publish it");
      }
      return request.port();
    }
    Set<Integer> used =
        repository.findAll().stream()
            .filter(d -> !d.getId().equals(excludedId))
            .map(Daemon::getPort)
            .collect(Collectors.toSet());
    for (int port = base; port <= end; port++) {
      if (!used.contains(port)) {
        return port;
      }
    }
    throw ApiException.conflict(
        "daemon_port_range_full",
        "No free port left in the published " + request.proto() + " range " + base + "-" + end);
  }

  private static boolean isUdp(Protocol proto) {
    return proto == Protocol.udp || proto == Protocol.udp6;
  }

  /** Rejects an unknown node id so daemons never point at a deleted node. */
  private void validateNode(String nodeId) {
    if (nodeId != null && !nodeId.isBlank()) {
      nodeRegistryService.requireNode(nodeId);
    }
  }

  /** Reads the legacy single-daemon network setting, falling back to defaults. */
  private ServerConfig legacyNetworkConfig() {
    return settingRepository
        .findById(NETWORK_SETTING_KEY)
        .map(s -> generator.fromJson(s.getValue()))
        .orElseGet(ServerConfig::defaults);
  }

  /**
   * Resolves the server-wide traffic mode from the {@code network_mode} setting. Values are stored
   * JSON-encoded, so surrounding quotes are stripped. Anything other than "routed" means NAT.
   */
  public String networkMode() {
    return settingRepository
        .findById(SettingKeys.NETWORK_MODE)
        .map(ServerSetting::getValue)
        .map(v -> v == null ? "nat" : v.trim())
        .map(
            v ->
                v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"'
                    ? v.substring(1, v.length() - 1)
                    : v)
        .filter(v -> v.equals("nat") || v.equals("routed"))
        .orElse("nat");
  }

  private Daemon toEntity(ServerConfig config, int daemonIndex) {
    return Daemon.builder()
        .id(UUID.randomUUID().toString())
        .daemonIndex(daemonIndex)
        .name("Primary")
        .port(config.port())
        .proto(config.proto() == null ? Protocol.udp : config.proto())
        .subnet(config.subnet())
        .subnetMask(config.subnetMask())
        .dnsServers(config.dnsServers())
        .domain(config.domain())
        .extraRoutes(config.extraRoutes())
        .fullTunnel(config.fullTunnel())
        .clientCertNotRequired(config.clientCertNotRequired())
        .authUserPass(config.authUserPass())
        .adminHost(config.adminHost())
        .ipv6Enabled(config.ipv6Enabled())
        .ipv6Subnet(config.ipv6Subnet())
        .enabled(true)
        .createdAt(Instant.now())
        .build();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * Create/update payload for an OpenVPN daemon. Port is optional: null auto-assigns the next free
   * port within the published range of the chosen protocol.
   */
  public record DaemonRequest(
      @Min(0) @Max(31) int daemonIndex,
      String name,
      @Min(1) @Max(65535) Integer port,
      @NotNull Protocol proto,
      @NotBlank String subnet,
      @NotBlank String subnetMask,
      List<String> dnsServers,
      String domain,
      List<String> extraRoutes,
      boolean fullTunnel,
      boolean clientCertNotRequired,
      boolean authUserPass,
      String adminHost,
      String nodeId,
      boolean ipv6Enabled,
      String ipv6Subnet,
      boolean enabled) {}
}
