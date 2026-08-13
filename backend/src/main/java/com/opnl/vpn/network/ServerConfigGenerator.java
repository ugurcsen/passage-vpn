package com.opnl.vpn.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.ApiException;
import java.io.IOException;
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
      String networkMode) {
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
        .replace("__SUBNET__", config.subnet())
        .replace("__SUBNET_MASK__", config.subnetMask())
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

  private String renderDnsPushes(ServerConfig config) {
    StringBuilder sb = new StringBuilder();
    String dnsmasq = dnsmasqServerIp(config.subnet());
    if (dnsmasq != null) {
      sb.append("push \"dhcp-option DNS ").append(dnsmasq).append("\"\n");
    }
    for (String dns : config.dnsServers()) {
      if (!dns.equals(dnsmasq)) {
        sb.append("push \"dhcp-option DNS ").append(dns).append("\"\n");
      }
    }
    if (config.domain() != null && !config.domain().isBlank()) {
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

  private String renderRoutePushes(ServerConfig config) {
    if (config.fullTunnel()) {
      return "push \"redirect-gateway def1 bypass-dhcp\"";
    }
    // Split tunnel: only push explicitly configured routes.
    StringBuilder sb = new StringBuilder();
    for (String route : config.extraRoutes()) {
      sb.append("push \"route ").append(route).append("\"\n");
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
   * domain with the pinned IPv4 address, so clients always resolve to an address the firewall rules
   * know about. Input is a map of domain to its resolved IPv4 addresses.
   */
  public String renderDnsmasqConfig(java.util.Map<String, java.util.Set<String>> domainToIps) {
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
          sb.append("address=/").append(entry.getKey()).append("/").append(ip).append("\n");
          pinned = true;
        }
      }
      if (pinned) {
        // Make dnsmasq authoritative for the domain: A queries are answered from
        // the pins above while AAAA queries return NODATA, so dual-stack clients
        // never leave the reach of the IPv4-only firewall rules.
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
  public String renderDnsOverridesConfig(java.util.List<com.opnl.vpn.dns.DnsRecord> records) {
    StringBuilder sb =
        new StringBuilder(
            "# Generated by the OpenVPN management panel backend.\n"
                + "# DNS overrides: internal hostnames answered authoritatively for VPN clients.\n"
                + "# These names resolve only on the VPN; public DNS does not know them.\n"
                + "# Do not edit manually — rewritten whenever DNS overrides change.\n");
    for (com.opnl.vpn.dns.DnsRecord record : records) {
      if (record.getHostname() == null || record.getIpv4() == null) {
        continue;
      }
      sb.append("address=/")
          .append(record.getHostname())
          .append("/")
          .append(record.getIpv4())
          .append("\n");
      sb.append("server=/").append(record.getHostname()).append("/\n");
    }
    return sb.toString();
  }
}
