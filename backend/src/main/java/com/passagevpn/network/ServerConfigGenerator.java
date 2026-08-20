package com.passagevpn.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.common.ApiException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Renders a {@link ServerConfig} into an OpenVPN server configuration file by substituting
 * placeholders in the daemon.conf template.
 */
@Slf4j
@Service
public class ServerConfigGenerator {

  private static final String TEMPLATE_PATH = "templates/daemon.conf";

  private final ObjectMapper objectMapper;

  public ServerConfigGenerator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String render(
      ServerConfig config,
      String pkiDir,
      String ccdDir,
      String scriptsDir,
      String logDir,
      String networkMode,
      String mgmtPwdFile) {
    String template;
    try {
      template = new ClassPathResource(TEMPLATE_PATH).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ApiException.internal(
          "template_missing", "Cannot load daemon template: " + e.getMessage());
    }

    int mgmtPort = 7505 + config.daemonIndex();

    return template
        .replace("__PORT__", String.valueOf(config.port()))
        .replace("__PROTO__", config.proto().name())
        .replace("__MGMT_PORT__", String.valueOf(mgmtPort))
        .replace("__MGMT_PWD_FILE__", mgmtPwdFile == null ? "" : mgmtPwdFile)
        .replace("__SUBNET__", config.subnet())
        .replace("__SUBNET_MASK__", config.subnetMask())
        .replace("__IPV6_SERVER__", renderIpv6Server(config))
        .replace("__IPV6_ROUTE_PUSH__", renderIpv6RoutePush(config))
        .replace("__NETWORK_MODE__", normalizeMode(networkMode))
        .replace("__PKI_DIR__", pkiDir)
        .replace("__CCD_DIR__", ccdDir)
        .replace("__SCRIPTS_DIR__", scriptsDir)
        .replace("__LOG_DIR__", logDir)
        .replace(
            "__AUTH_VERIFY__",
            config.authUserPass()
                ? "auth-user-pass-verify "
                    + scriptsDir
                    + "/verify-user-pass.sh via-env\n"
                    + "client-crresponse "
                    + scriptsDir
                    + "/verify-user-pass.sh\n"
                    + "auth-gen-token 43200"
                : "")
        .replace(
            "__VERIFY_CLIENT_CERT__",
            config.clientCertNotRequired()
                ? "verify-client-cert none"
                : "verify-client-cert require")
        .replace("__DNS_PUSH__", renderDnsPushes(config))
        .replace("__ROUTE_PUSH__", renderRoutePushes(config))
        .replace("__EXTRA_PUSH__", renderExtraPushes(config));
  }

  private static String normalizeMode(String mode) {
    return "routed".equalsIgnoreCase(mode) ? "routed" : "nat";
  }

  /** The {@code server-ipv6} line, or empty when dual-stack is disabled. */
  private String renderIpv6Server(ServerConfig config) {
    if (!ipv6Active(config)) {
      return "";
    }
    return "server-ipv6 " + config.ipv6Subnet().trim();
  }

  /**
   * The IPv6 routing push, or empty when dual-stack is disabled: full-tunnel clients redirect all
   * IPv6 traffic into the tunnel, split-tunnel clients only route the VPN subnet.
   */
  private String renderIpv6RoutePush(ServerConfig config) {
    if (!ipv6Active(config)) {
      return "";
    }
    if (config.fullTunnel()) {
      return "push \"redirect-gateway ipv6\"";
    }
    return "push \"route-ipv6 " + config.ipv6Subnet().trim() + "\"";
  }

  /** True when dual-stack is configured with a usable IPv6 subnet. */
  private boolean ipv6Active(ServerConfig config) {
    return config.ipv6Enabled() && config.ipv6Subnet() != null && !config.ipv6Subnet().isBlank();
  }

  private String renderDnsPushes(ServerConfig config) {
    StringBuilder sb = new StringBuilder();
    String dnsmasq = dnsmasqServerIp(config.subnet());
    boolean hasDomain = config.domain() != null && !config.domain().isBlank();

    if (hasDomain) {
      // DNS option v2 (OpenVPN3 v3.11+): scoped resolvers via SupplementalMatchDomains.
      // Fixes split-DNS on macOS/iOS where the old --dhcp-option approach incorrectly
      // binds DNS to en0 (Wi-Fi) instead of the VPN tun interface.
      if (dnsmasq != null) {
        sb.append("push \"dns server 0 address ").append(dnsmasq).append("\"\n");
        sb.append("push \"dns server 0 resolve-domains .")
            .append(config.domain())
            .append("\"\n");
      }
      int priority = 1;
      for (String dns : config.dnsServers()) {
        if (dnsmasq != null && dns.equals(dnsmasq)) {
          continue;
        }
        sb.append("push \"dns server ").append(priority).append(" address ").append(dns)
            .append("\"\n");
        priority++;
      }
      sb.append("push \"dns search-domains ").append(config.domain()).append("\"\n");
    } else {
      // Legacy fallback: --dhcp-option for older clients or when no domain is configured.
      if (dnsmasq != null) {
        sb.append("push \"dhcp-option DNS ").append(dnsmasq).append("\"\n");
      }
      for (String dns : config.dnsServers()) {
        if (dnsmasq != null && dns.equals(dnsmasq)) {
          continue;
        }
        sb.append("push \"dhcp-option DNS ").append(dns).append("\"\n");
      }
    }

    if (ipv6Active(config)) {
      String dnsmasq6 = ipv6ServerIp(config.ipv6Subnet());
      if (dnsmasq6 != null) {
        sb.append("push \"dhcp-option DNS6 ").append(dnsmasq6).append("\"\n");
      }
    }

    if (hasDomain) {
      sb.append("push \"dhcp-option DOMAIN ").append(config.domain()).append("\"\n");
    }
    return sb.toString();
  }

  /**
   * Computes the resolver address the openvpn container's dnsmasq serves for this daemon: the tun
   * server IP, which OpenVPN assigns as the pool network + 1. Pushed to clients before the
   * configured public servers so domain-pinned rules resolve through dnsmasq.
   */
  static String dnsmasqServerIp(String subnet) {
    if (subnet == null || subnet.isBlank()) {
      return null;
    }
    String[] octets = subnet.split("\\.");
    if (octets.length != 4) {
      return null;
    }
    long value = 0;
    for (String octet : octets) {
      int b;
      try {
        b = Integer.parseInt(octet);
      } catch (NumberFormatException e) {
        return null;
      }
      if (b < 0 || b > 255) {
        return null;
      }
      value = value * 256 + b;
    }
    if (value == 0xFFFFFFFFL) {
      return null;
    }
    long ip = value + 1;
    return (ip >> 24) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
  }

  /**
   * Computes the tun server IPv6 address OpenVPN assigns for a {@code server-ipv6} subnet: the
   * network base + 1 (e.g. {@code fd00:1::/64} → {@code fd00:1::1}). The openvpn container's
   * dnsmasq listens on this address so dual-stack clients reach the resolver over IPv6. Returns
   * null when the CIDR is malformed or missing.
   */
  public static String ipv6ServerIp(String subnet) {
    if (subnet == null || subnet.isBlank()) {
      return null;
    }
    String[] parts = subnet.trim().split("/");
    if (parts.length != 2) {
      return null;
    }
    try {
      int prefix = Integer.parseInt(parts[1]);
      if (prefix < 1 || prefix > 128) {
        return null;
      }
      Inet6Address address = (Inet6Address) InetAddress.getByName(parts[0]);
      byte[] bytes = address.getAddress();
      int fullBytes = prefix / 8;
      int remBits = prefix % 8;
      for (int i = fullBytes; i < 16; i++) {
        bytes[i] = 0;
      }
      if (remBits != 0) {
        bytes[fullBytes] &= (byte) (0xFF << (8 - remBits));
      }
      byte[] plus = new BigInteger(1, bytes).add(BigInteger.ONE).toByteArray();
      byte[] result = new byte[16];
      System.arraycopy(plus, Math.max(0, plus.length - 16), result, 0, 16);
      return InetAddress.getByAddress(result).getHostAddress();
    } catch (Exception e) {
      return null;
    }
  }

  private String renderRoutePushes(ServerConfig config) {
    if (config.fullTunnel()) {
      return "push \"redirect-gateway def1 bypass-dhcp\"";
    }
    // Split tunnel: only push explicitly configured routes.
    StringBuilder sb = new StringBuilder();
    for (String route : config.extraRoutes()) {
      if (route.contains(":")) {
        sb.append("push \"route-ipv6 ").append(route).append("\"\n");
      } else {
        sb.append("push \"route ").append(route).append("\"\n");
      }
    }
    return sb.toString();
  }

  private String renderExtraPushes(ServerConfig config) {
    // Future: extra push options (search domains, MTU, etc.)
    return "";
  }

  /** Serializes a config to JSON for persistence. */
  public String toJson(ServerConfig config) {
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw ApiException.internal("serialize", "Cannot serialize server config: " + e.getMessage());
    }
  }

  /** Deserializes a config from persisted JSON; falls back to defaults. */
  public ServerConfig fromJson(String json) {
    if (json == null || json.isBlank()) {
      return ServerConfig.defaults();
    }
    try {
      return objectMapper.readValue(json, ServerConfig.class);
    } catch (JsonProcessingException e) {
      log.warn("Cannot parse stored server config, using defaults: {}", e.getMessage());
      return ServerConfig.defaults();
    }
  }

  /** Renders the DNS block for a single push (used by unit tests). */
  public List<String> dnsList(ServerConfig config) {
    return config.dnsServers();
  }

  /**
   * Renders the dnsmasq domain-pinning config consumed by the openvpn container's dnsmasq instance.
   * Each entry of the form {@code address=/domain/ip} makes dnsmasq answer authoritatively for the
   * domain with the pinned address, so clients always resolve to an address the firewall rules know
   * about. Input is a map of domain to its resolved addresses (IPv4 and, when dual-stack is
   * enabled, IPv6).
   */
  public String renderDnsmasqConfig(java.util.Map<String, java.util.Set<String>> domainToIps) {
    return renderDnsmasqConfig(domainToIps, false);
  }

  /**
   * Same as {@link #renderDnsmasqConfig(Map)} with explicit dual-stack control: IPv6 addresses are
   * pinned only when {@code ipv6Enabled} is true, otherwise the domain stays authoritative with A
   * answers from the IPv4 pins while AAAA queries return NODATA (clients never leave the reach of
   * the IPv4-only firewall rules).
   */
  public String renderDnsmasqConfig(
      java.util.Map<String, java.util.Set<String>> domainToIps, boolean ipv6Enabled) {
    StringBuilder sb =
        new StringBuilder(
            "# Generated by the OpenVPN management panel backend.\n"
                + "# Domain pinning for access rules: clients always resolve these domains to\n"
                + "# the addresses below, which the per-client firewall rules also match.\n"
                + "# Do not edit manually — rewritten whenever access rules change.\n");
    for (var entry : domainToIps.entrySet()) {
      boolean pinned = false;
      for (String ip : entry.getValue()) {
        if (ip != null && !ip.isBlank()) {
          if (ip.contains(":") && !ipv6Enabled) {
            continue;
          }
          sb.append("address=/").append(entry.getKey()).append("/").append(ip).append("\n");
          pinned = true;
        }
      }
      if (pinned) {
        // Make dnsmasq authoritative for the domain: A/AAAA queries are answered
        // from the pins above, keeping the DNS answer and the per-client firewall
        // rules (IPv4 and, when enabled, IPv6) in agreement.
        sb.append("server=/").append(entry.getKey()).append("/\n");
      }
    }
    return sb.toString();
  }

  /**
   * Renders the dnsmasq DNS-override config consumed by the openvpn container's dnsmasq instance.
   * Each record yields an {@code address=/hostname/ip} pin plus an authoritative {@code server=/}
   * entry, exactly like domain pinning. The shared resolver serves these names to every VPN client;
   * access to the addresses is enforced per-client by the firewall (scope denies). Hostnames are
   * unique, so one address per record.
   */
  public String renderDnsOverridesConfig(java.util.List<com.passagevpn.dns.DnsRecord> records) {
    return renderDnsOverridesConfig(records, false);
  }

  /**
   * Same as {@link #renderDnsOverridesConfig(List)} with explicit dual-stack control: a record's
   * IPv6 address is pinned only when {@code ipv6Enabled} is true, so scoped overrides never answer
   * AAAA outside the reach of the IPv6 firewall rules.
   */
  public String renderDnsOverridesConfig(
      java.util.List<com.passagevpn.dns.DnsRecord> records, boolean ipv6Enabled) {
    StringBuilder sb =
        new StringBuilder(
            "# Generated by the OpenVPN management panel backend.\n"
                + "# DNS overrides: internal hostnames answered authoritatively for VPN clients.\n"
                + "# These names resolve only on the VPN; public DNS does not know them.\n"
                + "# Do not edit manually — rewritten whenever DNS overrides change.\n");
    for (com.passagevpn.dns.DnsRecord record : records) {
      if (record.getHostname() == null || record.getIpv4() == null) {
        continue;
      }
      sb.append("address=/")
          .append(record.getHostname())
          .append("/")
          .append(record.getIpv4())
          .append("\n");
      if (ipv6Enabled && record.getIpv6() != null && !record.getIpv6().isBlank()) {
        sb.append("address=/")
            .append(record.getHostname())
            .append("/")
            .append(record.getIpv6())
            .append("\n");
      }
      sb.append("server=/").append(record.getHostname()).append("/\n");
    }
    return sb.toString();
  }
}
