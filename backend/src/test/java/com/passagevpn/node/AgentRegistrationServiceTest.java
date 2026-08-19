package com.passagevpn.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.config.AgentProperties;
import com.passagevpn.config.PassageProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end-ish tests for the agent registration/heartbeat loop against a real HTTP server. */
class AgentRegistrationServiceTest {

  private HttpServer server;
  private final List<String> requests = new ArrayList<>();
  private final List<String> tokens = new ArrayList<>();
  private int registerStatus = 200;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newFixedThreadPool(2));
    server.createContext("/internal/node/", this::handle);
    server.start();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(path + " " + body);
    tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
    int status = path.endsWith("/register") ? registerStatus : 200;
    String response = path.endsWith("/register") ? "{\"nodeId\":\"n-1\"}" : "{}";
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, response.getBytes(StandardCharsets.UTF_8).length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response.getBytes(StandardCharsets.UTF_8));
    }
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private AgentRegistrationService service() {
    AgentProperties properties =
        new AgentProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "edge-eu",
            "openvpn",
            7505,
            null,
            "mgmt-pass",
            30,
            60,
            null,
            null,
            null);
    PassageProperties opnl =
        new PassageProperties("./data", "OpenVPN Panel", "secret-token", null, null, null, null);
    return new AgentRegistrationService(
        properties, opnl, new ObjectMapper(), mock(AgentTls.class), HttpClient.newHttpClient());
  }

  @Test
  void registerThenHeartbeat() {
    AgentRegistrationService service = service();
    service.tick();
    service.tick();

    assertEquals(2, requests.size());
    String registerBody = requests.get(0);
    assertTrue(registerBody.startsWith("/internal/node/register "));
    assertTrue(registerBody.contains("\"name\":\"edge-eu\""));
    assertTrue(registerBody.contains("\"mgmtPortBase\":7505"));
    assertTrue(registerBody.contains("\"mgmtPassword\":\"mgmt-pass\""));
    assertEquals("secret-token", tokens.get(0));
    assertTrue(requests.get(1).startsWith("/internal/node/heartbeat "));
    assertTrue(requests.get(1).contains("\"nodeId\":\"n-1\""));
  }

  @Test
  void retriesRegisterUntilSuccess() {
    registerStatus = 500;
    AgentRegistrationService service = service();
    service.tick();
    assertEquals(1, requests.size());
    assertTrue(requests.get(0).startsWith("/internal/node/register "));

    registerStatus = 200;
    service.tick();
    service.tick();
    assertTrue(requests.get(1).startsWith("/internal/node/register "));
    assertTrue(requests.get(2).startsWith("/internal/node/heartbeat "));
  }

  @Test
  void missingNodeNameDoesNotCallCentral() {
    AgentProperties properties =
        new AgentProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "",
            "openvpn",
            7505,
            null,
            "mgmt-pass",
            30,
            60,
            null,
            null,
            null);
    PassageProperties opnl =
        new PassageProperties("./data", "OpenVPN Panel", "secret-token", null, null, null, null);
    AgentRegistrationService service =
        new AgentRegistrationService(
            properties, opnl, new ObjectMapper(), mock(AgentTls.class), HttpClient.newHttpClient());
    service.tick();
    assertEquals(0, requests.size());
    assertNull(service.nodeId());
  }

  @Test
  void adminIpIsSentWhenProvided() {
    AgentProperties properties =
        new AgentProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "edge-eu",
            "openvpn",
            7505,
            "10.0.0.5",
            "mgmt-pass",
            30,
            60,
            null,
            null,
            null);
    PassageProperties opnl =
        new PassageProperties("./data", "OpenVPN Panel", "secret-token", null, null, null, null);
    AgentRegistrationService service =
        new AgentRegistrationService(
            properties, opnl, new ObjectMapper(), mock(AgentTls.class), HttpClient.newHttpClient());
    service.tick();
    assertTrue(requests.get(0).contains("\"adminIp\":\"10.0.0.5\""));
  }
}
