package com.opnl.vpn.ccd;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.ServerConfigGenerator;
import com.opnl.vpn.network.ServerSettingRepository;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages OpenVPN client-config-dir (CCD) files. A CCD file is written only when a user has a
 * static IP assigned: with {@code ccd-exclusive} a present file takes over all addressing, so users
 * without a static IP must not have one. Per-user pushes (DNS, routes) are layered on top.
 */
@Slf4j
@Service
public class CcdService {

  private final UserRepository userRepository;
  private final SettingsService settingsService;
  private final ServerSettingRepository serverSettingRepository;
  private final ServerConfigGenerator serverConfigGenerator;
  private final OpnlProperties properties;

  public CcdService(
      UserRepository userRepository,
      SettingsService settingsService,
      ServerSettingRepository serverSettingRepository,
      ServerConfigGenerator serverConfigGenerator,
      OpnlProperties properties) {
    this.userRepository = userRepository;
    this.settingsService = settingsService;
    this.serverSettingRepository = serverSettingRepository;
    this.serverConfigGenerator = serverConfigGenerator;
    this.properties = properties;
  }

  /** Assigns a static VPN IP to a user and rewrites their CCD file. */
  @Transactional
  public void setStaticIp(String userId, String ip) {
    if (ip == null || ip.isBlank()) {
      clearStaticIp(userId);
      return;
    }
    validate(ip);
    userRepository
        .findByStaticIp(ip)
        .ifPresent(
            other -> {
              if (!other.getId().equals(userId)) {
                throw ApiException.conflict(
                    "static_ip_in_use",
                    "Static IP " + ip + " is already assigned to " + other.getUsername());
              }
            });
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
    user.setStaticIp(ip);
    userRepository.save(user);
    writeUserCcd(user);
  }

  /** Removes the static IP and deletes the user's CCD file. */
  @Transactional
  public void clearStaticIp(String userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
    user.setStaticIp(null);
    userRepository.save(user);
    deleteCcd(user.getUsername());
  }

  /**
   * Allocates the next free static IP from the user's most specific group pool and assigns it.
   * Returns the allocated IP. Throws {@code no_ip_pool} when no group defines a pool and {@code
   * pool_exhausted} when every address in the pool is already in use.
   */
  @Transactional
  public String allocateFromGroupPool(String userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
    String pool = groupPoolFor(user.getId());
    if (pool == null || pool.isBlank()) {
      throw ApiException.badRequest(
          "no_ip_pool", "No static IP pool configured for the user's groups");
    }
    PoolRange range = parsePool(pool);
    String candidate = findFreeIp(range, user);
    if (candidate == null) {
      throw ApiException.conflict("pool_exhausted", "Static IP pool exhausted for " + pool);
    }
    user.setStaticIp(candidate);
    userRepository.save(user);
    writeUserCcd(user);
    return candidate;
  }

  /** The static IP pool of the user's most specific group with one defined, or null. */
  public String groupPoolFor(String userId) {
    for (String groupId : settingsService.groupChainForUser(userId)) {
      Object pool = settingsService.groupSettings(groupId).get(SettingKeys.STATIC_IP_POOL);
      if (pool != null && !pool.toString().isBlank()) {
        return pool.toString();
      }
    }
    return null;
  }

  /** Validates a pool expression; throws {@code invalid_ip_pool} when malformed. */
  public void validatePool(String pool) {
    if (pool == null || pool.isBlank()) {
      return;
    }
    parsePool(pool);
  }

  /** Regenerates all CCD files and removes files for users that no longer have one. */
  @Transactional(readOnly = true)
  public void syncAll() {
    List<User> users = userRepository.findAll();
    Set<String> names =
        users.stream().map(User::getUsername).collect(Collectors.toCollection(HashSet::new));
    Path dir = ccdDir();
    if (Files.isDirectory(dir)) {
      try (var stream = Files.list(dir)) {
        stream.filter(p -> !names.contains(p.getFileName().toString())).forEach(p -> deleteFile(p));
      } catch (IOException e) {
        log.warn("Cannot list CCD dir {}: {}", dir, e.getMessage());
      }
    }
    for (User user : users) {
      writeUserCcd(user);
    }
  }

  /** Writes or removes a user's CCD file depending on whether a static IP is set. */
  @Transactional(readOnly = true)
  public void writeUserCcd(User user) {
    String staticIp = user.getStaticIp();
    if (staticIp == null || staticIp.isBlank()) {
      deleteCcd(user.getUsername());
      return;
    }
    List<String> lines = new ArrayList<>();
    lines.add("ifconfig-push " + staticIp + " " + subnetMask());
    Map<String, Object> effective = settingsService.effectiveForUser(user.getId());
    if (isFullTunnel(effective)) {
      lines.add("push \"redirect-gateway def1 bypass-dhcp\"");
    } else {
      appendRoutePushes(lines, effective);
    }
    appendDns(lines, effective);
    writeCcd(user.getUsername(), lines);
  }

  public void deleteCcd(String commonName) {
    if (commonName == null || commonName.isBlank()) {
      return;
    }
    deleteFile(ccdDir().resolve(commonName));
  }

  // ---- helpers ------------------------------------------------------------

  private void validate(String ip) {
    InetAddress address;
    try {
      address = InetAddress.getByName(ip);
    } catch (Exception e) {
      throw ApiException.badRequest("invalid_static_ip", "Not a valid IP address: " + ip);
    }
    if (!(address instanceof Inet4Address)) {
      throw ApiException.badRequest("invalid_static_ip", "Only IPv4 static IPs are supported");
    }
    var network = serverConfigGenerator.fromJson(networkSettingValue());
    try {
      int net = toInt(InetAddress.getByName(network.subnet()).getAddress());
      int mask = toInt(InetAddress.getByName(network.subnetMask()).getAddress());
      int addr = toInt(address.getAddress());
      if ((addr & mask) != net) {
        throw ApiException.badRequest(
            "invalid_static_ip", "IP is outside the VPN subnet " + network.subnet());
      }
      int broadcast = net | ~mask;
      if (addr == net || addr == broadcast) {
        throw ApiException.badRequest(
            "invalid_static_ip", "IP must not be the network or broadcast address");
      }
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.badRequest("invalid_static_ip", "Cannot validate IP against subnet");
    }
  }

  private static int toInt(byte[] octets) {
    return ((octets[0] & 0xff) << 24)
        | ((octets[1] & 0xff) << 16)
        | ((octets[2] & 0xff) << 8)
        | (octets[3] & 0xff);
  }

  private record PoolRange(long start, long end) {}

  /** Parses a pool of the form "start-end", both IPv4 addresses (start <= end). */
  private PoolRange parsePool(String pool) {
    String[] parts = pool.trim().split("-");
    if (parts.length != 2) {
      throw ApiException.badRequest(
          "invalid_ip_pool", "IP pool must be of the form 10.8.0.100-10.8.0.199");
    }
    long start = toLong(parts[0].trim());
    long end = toLong(parts[1].trim());
    if (start > end) {
      throw ApiException.badRequest(
          "invalid_ip_pool", "IP pool start must not be greater than its end");
    }
    long[] bounds = subnetHostBounds();
    if (bounds == null) {
      throw ApiException.badRequest(
          "invalid_ip_pool", "Cannot validate pool against the VPN subnet");
    }
    if (start < bounds[0] || end > bounds[1] || start == bounds[0] || end == bounds[1]) {
      throw ApiException.badRequest(
          "invalid_ip_pool", "IP pool must be host addresses inside the VPN subnet");
    }
    return new PoolRange(start, end);
  }

  /** Resolves the server VPN subnet's [network, broadcast] addresses as longs, or null. */
  private long[] subnetHostBounds() {
    try {
      var network = serverConfigGenerator.fromJson(networkSettingValue());
      long net = toInt(InetAddress.getByName(network.subnet()).getAddress()) & 0xffff_ffffL;
      long mask = toInt(InetAddress.getByName(network.subnetMask()).getAddress()) & 0xffff_ffffL;
      return new long[] {net, (net | ~mask) & 0xffff_ffffL};
    } catch (Exception e) {
      return null;
    }
  }

  private long toLong(String ip) {
    long value;
    try {
      InetAddress address = InetAddress.getByName(ip);
      if (!(address instanceof Inet4Address)) {
        throw ApiException.badRequest("invalid_ip_pool", "Only IPv4 pool addresses are supported");
      }
      value = toInt(address.getAddress()) & 0xffff_ffffL;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.badRequest("invalid_ip_pool", "Invalid pool address: " + ip);
    }
    if (value == 0 || value == 0xffff_ffffL) {
      throw ApiException.badRequest("invalid_ip_pool", "Invalid pool address: " + ip);
    }
    return value;
  }

  /** First free address in the range not assigned to another user (and not the user's own). */
  private String findFreeIp(PoolRange range, User self) {
    Set<String> used =
        userRepository.findAll().stream()
            .map(User::getStaticIp)
            .filter(ip -> ip != null && !ip.isBlank())
            .filter(ip -> !(self.getStaticIp() != null && self.getStaticIp().equals(ip)))
            .collect(Collectors.toSet());
    long guard = range.end - range.start + 1;
    if (guard > 65536) {
      throw ApiException.badRequest("invalid_ip_pool", "IP pool range is too large");
    }
    for (long candidate = range.start; candidate <= range.end; candidate++) {
      String ip = formatIp(candidate);
      if (!used.contains(ip)) {
        return ip;
      }
    }
    return null;
  }

  private String formatIp(long value) {
    return ((value >> 24) & 0xff)
        + "."
        + ((value >> 16) & 0xff)
        + "."
        + ((value >> 8) & 0xff)
        + "."
        + (value & 0xff);
  }

  private void appendDns(List<String> lines, Map<String, Object> effective) {
    String dns = stringSetting(effective, SettingKeys.DNS_SERVERS);
    if (dns != null && !dns.isBlank()) {
      for (String server : dns.split(",")) {
        if (!server.isBlank()) {
          lines.add("push \"dhcp-option DNS " + server.trim() + "\"");
        }
      }
    }
    String domain = stringSetting(effective, SettingKeys.DNS_DOMAIN);
    if (domain != null && !domain.isBlank()) {
      lines.add("push \"dhcp-option DOMAIN " + domain.trim() + "\"");
    }
  }

  private void appendRoutePushes(List<String> lines, Map<String, Object> effective) {
    String routes = stringSetting(effective, SettingKeys.ROUTE_RESTRICTION);
    if (routes == null || routes.isBlank()) {
      return;
    }
    for (String cidr : routes.split(",")) {
      String trimmed = cidr.trim();
      if (!trimmed.isBlank()) {
        lines.add("push \"route " + trimmed + "\"");
      }
    }
  }

  /**
   * True when the account should get full tunnel (all traffic through the VPN). Resolution: the
   * effective `tunnel_mode` setting (user &gt; group &gt; server); when unset, falls back to the
   * server network config's global {@code fullTunnel} flag.
   */
  private boolean isFullTunnel(Map<String, Object> effective) {
    String mode = stringSetting(effective, SettingKeys.TUNNEL_MODE);
    if (mode != null && !mode.isBlank()) {
      return "full".equalsIgnoreCase(mode.trim());
    }
    return serverConfigGenerator.fromJson(networkSettingValue()).fullTunnel();
  }

  private String stringSetting(Map<String, Object> effective, String key) {
    Object value = effective.get(key);
    return value == null ? null : value.toString();
  }

  private String networkSettingValue() {
    return serverSettingRepository.findById("network").map(s -> s.getValue()).orElse(null);
  }

  private String subnetMask() {
    return serverConfigGenerator.fromJson(networkSettingValue()).subnetMask();
  }

  private Path ccdDir() {
    return Path.of(properties.openvpn().ccdDir());
  }

  private void writeCcd(String commonName, List<String> lines) {
    try {
      Path dir = ccdDir();
      Files.createDirectories(dir);
      Files.write(dir.resolve(commonName), lines);
      log.debug("Wrote CCD for {}", commonName);
    } catch (IOException e) {
      throw ApiException.internal("ccd_write_failed", "Cannot write CCD for " + commonName);
    }
  }

  private void deleteFile(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Cannot delete CCD file {}: {}", path, e.getMessage());
    }
  }
}
