package com.opnl.vpn.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.config.AgentProperties;
import com.opnl.vpn.config.OpnlProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Registers this gateway with the central backend and keeps it online via periodic heartbeats.
 * Active only under the {@code agent} Spring profile; the agent runs next to its own OpenVPN
 * gateway and the central backend routes status/kill/monitor directly to its management sockets.
 *
 * <p>Registration is idempotent by node name on the central side. Until it succeeds the service
 * retries every heartbeat tick; afterwards each tick sends a heartbeat.
 */
@Slf4j
@Service
@Profile("agent")
public class AgentRegistrationService {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

  private final AgentProperties properties;
  private final AgentTls agentTls;
  private final String internalToken;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  private volatile String nodeId;

  /** Package-visible for tests. */
  String nodeId() {
    return nodeId;
  }

  public AgentRegistrationService(
      AgentProperties properties,
      OpnlProperties opnlProperties,
      ObjectMapper objectMapper,
      AgentTls agentTls) {
    this(properties, opnlProperties, objectMapper, agentTls, clientFor(agentTls));
  }

  AgentRegistrationService(
      AgentProperties properties,
      OpnlProperties opnlProperties,
      ObjectMapper objectMapper,
      AgentTls agentTls,
      HttpClient httpClient) {
    this.properties = properties;
    this.agentTls = agentTls;
    this.internalToken = opnlProperties.internalToken();
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  private static HttpClient clientFor(AgentTls agentTls) {
    if (!agentTls.configured()) {
      return HttpClient.newHttpClient();
    }
    return HttpClient.newBuilder()
        .sslContext(agentTls.sslContext())
        .connectTimeout(HTTP_TIMEOUT)
        .build();
  }

  @Scheduled(fixedDelayString = "${opnl.agent.heartbeat-seconds:30}s", initialDelay = 5_000)
  public void tick() {
    try {
      validateConfig();
      if (nodeId == null) {
        register();
      } else {
        heartbeat();
      }
    } catch (IllegalStateException e) {
      log.warn("Agent not started: {}", e.getMessage());
    } catch (Exception e) {
      log.warn("Agent tick failed: {}", e.getMessage());
    }
  }

  private void validateConfig() {
    if (properties.centralBaseUrl() == null || properties.centralBaseUrl().isBlank()) {
      throw new IllegalStateException("opnl.agent.central-base-url is required");
    }
    if (properties.nodeName() == null || properties.nodeName().isBlank()) {
      throw new IllegalStateException("opnl.agent.node-name is required");
    }
    if (properties.mgmtHost() == null || properties.mgmtHost().isBlank()) {
      throw new IllegalStateException("opnl.agent.mgmt-host is required");
    }
    if (properties.mgmtPortBase() < 1 || properties.mgmtPortBase() > 65535) {
      throw new IllegalStateException("opnl.agent.mgmt-port-base must be 1-65535");
    }
    if (properties.mgmtPassword() == null || properties.mgmtPassword().isBlank()) {
      throw new IllegalStateException("opnl.agent.mgmt-password is required");
    }
    if (properties.heartbeatSeconds() < 5 || properties.heartbeatSeconds() > 3600) {
      throw new IllegalStateException("opnl.agent.heartbeat-seconds must be 5-3600");
    }
    if (properties.centralBaseUrl().startsWith("https://") && !agentTls.configured()) {
      throw new IllegalStateException(
          "opnl.agent.tls-ca, opnl.agent.tls-cert and opnl.agent.tls-key are required for an https central-base-url");
    }
  }

  private void register() throws IOException, InterruptedException {
    String body =
        objectMapper.writeValueAsString(
            Map.of(
                "name",
                properties.nodeName(),
                "mgmtHost",
                properties.mgmtHost(),
                "mgmtPortBase",
                properties.mgmtPortBase(),
                "adminIp",
                properties.adminIp() == null ? "" : properties.adminIp(),
                "mgmtPassword",
                properties.mgmtPassword()));
    String response = post("/internal/node/register", body);
    if (response == null) {
      return;
    }
    nodeId = objectMapper.readTree(response).path("nodeId").asText(null);
    if (nodeId == null || nodeId.isBlank()) {
      log.warn("Agent registration response missing nodeId");
      nodeId = null;
      return;
    }
    log.info("Agent registered node '{}' as {}", properties.nodeName(), nodeId);
  }

  private void heartbeat() throws IOException, InterruptedException {
    String body = objectMapper.writeValueAsString(Map.of("nodeId", nodeId));
    if (post("/internal/node/heartbeat", body) == null) {
      return;
    }
    log.debug("Agent heartbeat sent for node {}", nodeId);
  }

  /**
   * POSTs JSON to a central /internal/node endpoint; returns the response body or null on error.
   */
  private String post(String path, String body) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(properties.centralBaseUrl() + path))
            .timeout(HTTP_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-Internal-Token", internalToken == null ? "" : internalToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      log.warn("Central returned {} for {}: {}", response.statusCode(), path, response.body());
      return null;
    }
    return response.body();
  }
}
