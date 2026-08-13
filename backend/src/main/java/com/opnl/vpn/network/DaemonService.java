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
import java.util.UUID;
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

  private final DaemonRepository repository;
  private final ServerSettingRepository settingRepository;
  private final ServerConfigGenerator generator;
  private final ConfigWriter configWriter;
  private final OpnlProperties properties;
  private final AuditLogService auditLogService;

  public DaemonService(
      DaemonRepository repository,
      ServerSettingRepository settingRepository,
      ServerConfigGenerator generator,
      ConfigWriter configWriter,
      OpnlProperties properties,
      AuditLogService auditLogService) {
    this.repository = repository;
    this.settingRepository = settingRepository;
    this.generator = generator;
    this.configWriter = configWriter;
    this.properties = properties;
    this.auditLogService = auditLogService;
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

  /** Rewrites every daemon config into the shared volume; disables remove their config file. */
  @Transactional
  public void writeAll() {
    ensurePrimary();
    String networkMode = networkMode();
    for (Daemon daemon : repository.findAllByOrderByDaemonIndexAsc()) {
      if (daemon.isEnabled()) {
        configWriter.writeDaemon(toServerConfig(daemon), generator, properties, networkMode);
      } else {
        configWriter.removeDaemon(daemon.getDaemonIndex());
      }
    }
  }

  @Transactional
  public Daemon create(DaemonRequest request) {
    validateUnique(request, null);
    Daemon daemon =
        Daemon.builder()
            .id(UUID.randomUUID().toString())
            .daemonIndex(request.daemonIndex())
            .name(blankToNull(request.name()))
            .port(request.port())
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
    validateUnique(request, id);
    daemon.setDaemonIndex(request.daemonIndex());
    daemon.setName(blankToNull(request.name()));
    daemon.setPort(request.port());
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
    configWriter.removeDaemon(daemon.getDaemonIndex());
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

  /** Whether the given daemon runs a dual-stack tunnel. */
  @Transactional(readOnly = true)
  public boolean ipv6Enabled(int daemonIndex) {
    return repository.findByDaemonIndex(daemonIndex).map(Daemon::isIpv6Enabled).orElse(false);
  }

  /** Whether the primary daemon runs a dual-stack tunnel (default for panel-wide features). */
  @Transactional(readOnly = true)
  public boolean primaryIpv6Enabled() {
    return ipv6Enabled(0);
  }

  /** Returns the daemon entity serving the given profile type, falling back to the primary. */
  @Transactional
  public Daemon entityForProfile(ProfileType type) {
    List<Daemon> daemons = list();
    Daemon match =
        switch (type) {
          case GENERIC -> firstMatching(d -> d.isEnabled() && d.isClientCertNotRequired());
          case AUTO_LOGIN ->
              firstMatching(
                  d -> d.isEnabled() && !d.isClientCertNotRequired() && !d.isAuthUserPass());
          case USER_LOCKED, SERVER_LOCKED ->
              firstMatching(
                  d -> d.isEnabled() && !d.isClientCertNotRequired() && d.isAuthUserPass());
        };
    if (match == null) {
      match = primary(daemons);
    }
    return match;
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

  private Daemon firstMatching(java.util.function.Predicate<Daemon> predicate) {
    return repository.findByEnabledTrueOrderByDaemonIndexAsc().stream()
        .filter(predicate)
        .findFirst()
        .orElse(null);
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

  private void validateUnique(DaemonRequest request, String excludedId) {
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
        .filter(d -> d.getPort() == request.port())
        .findAny()
        .ifPresent(
            d -> {
              throw ApiException.conflict(
                  "daemon_port_taken", "Port " + request.port() + " is already in use");
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
  private String networkMode() {
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

  /** Create/update payload for an OpenVPN daemon. */
  public record DaemonRequest(
      @Min(0) @Max(31) int daemonIndex,
      String name,
      @Min(1) @Max(65535) int port,
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
      boolean ipv6Enabled,
      String ipv6Subnet,
      boolean enabled) {}
}
