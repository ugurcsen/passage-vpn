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
    for (String dns : config.dnsServers()) {
      sb.append("push \"dhcp-option DNS ").append(dns).append("\"\n");
    }
    if (config.domain() != null && !config.domain().isBlank()) {
      sb.append("push \"dhcp-option DOMAIN ").append(config.domain()).append("\"\n");
    }
    return sb.toString();
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
}
