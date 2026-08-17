package com.passagevpn.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.common.GlobalExceptionHandler;
import com.passagevpn.internal.InternalTlsService;
import com.passagevpn.network.NodeRegistryService;
import com.passagevpn.network.OpenVpnNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer validation tests for the VPN node admin API. */
class NodeAdminControllerTest {

  private NodeRegistryService nodeRegistryService;
  private InternalTlsService tlsService;
  private MockMvc mvc;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    nodeRegistryService = mock(NodeRegistryService.class);
    tlsService = mock(InternalTlsService.class);
    objectMapper = new ObjectMapper();
    mvc =
        MockMvcBuilders.standaloneSetup(new NodeAdminController(nodeRegistryService, tlsService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private OpenVpnNodeDto dto() {
    return new OpenVpnNodeDto(
        "n1",
        "edge-eu",
        "vpn-eu.example.com",
        7505,
        "10.0.0.5",
        null,
        true,
        null,
        true,
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        false);
  }

  @Test
  void listReturnsNodes() throws Exception {
    when(nodeRegistryService.list()).thenReturn(List.of(dto()));
    mvc.perform(get("/api/admin/nodes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("edge-eu"))
        .andExpect(jsonPath("$[0].mgmtHost").value("vpn-eu.example.com"));
  }

  @Test
  void createDelegatesToService() throws Exception {
    when(nodeRegistryService.create(any())).thenReturn(dto());
    mvc.perform(
            post("/api/admin/nodes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "name", "edge-eu",
                            "mgmtHost", "vpn-eu.example.com",
                            "mgmtPortBase", 7505,
                            "adminIp", "10.0.0.5"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("edge-eu"));
  }

  @Test
  void updateDelegatesToServiceWithId() throws Exception {
    when(nodeRegistryService.update(eq("n1"), any())).thenReturn(dto());
    mvc.perform(
            put("/api/admin/nodes/n1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "name", "edge-eu",
                            "mgmtHost", "vpn-eu.example.com",
                            "mgmtPortBase", 7505,
                            "adminIp", "10.0.0.5"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("edge-eu"));
  }

  @Test
  void deleteDelegatesToService() throws Exception {
    mvc.perform(delete("/api/admin/nodes/n1")).andExpect(status().isOk());
    verify(nodeRegistryService).delete("n1");
  }

  @Test
  void setEnabledDelegatesToService() throws Exception {
    when(nodeRegistryService.setEnabled("n1", false)).thenReturn(dto());
    mvc.perform(post("/api/admin/nodes/n1/enabled").param("enabled", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void agentCertDelegatesToTlsService() throws Exception {
    OpenVpnNode node =
        OpenVpnNode.builder()
            .id("n1")
            .name("edge-eu")
            .mgmtHost("vpn-eu.example.com")
            .mgmtPortBase(7505)
            .enabled(true)
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
    when(nodeRegistryService.requireNode("n1")).thenReturn(node);
    when(tlsService.issueAgentCert("edge-eu", null))
        .thenReturn(
            new InternalTlsService.AgentCertificate(
                "edge-eu", "ca-pem", "cert-pem", "key-pem", new byte[] {1, 2, 3}, "bundle-pass"));
    mvc.perform(post("/api/admin/nodes/n1/agent-cert"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodeName").value("edge-eu"))
        .andExpect(jsonPath("$.caCert").value("ca-pem"))
        .andExpect(jsonPath("$.password").value("bundle-pass"));
    verify(tlsService).issueAgentCert("edge-eu", null);
  }
}
