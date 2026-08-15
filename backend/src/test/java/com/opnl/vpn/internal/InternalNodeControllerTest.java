package com.opnl.vpn.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.config.InternalProperties;
import com.opnl.vpn.network.NodeRegistryService;
import com.opnl.vpn.network.OpenVpnNode;
import com.opnl.vpn.node.NodeConfigBundleService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the node agent register/heartbeat endpoints (mTLS-protected). */
class InternalNodeControllerTest {

  private static final int MTLS_PORT = 9443;

  private NodeRegistryService nodeRegistryService;
  private ClientCertReader clientCertReader;
  private NodeConfigBundleService nodeConfigBundleService;
  private MockMvc mvc;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    nodeRegistryService = mock(NodeRegistryService.class);
    clientCertReader = mock(ClientCertReader.class);
    nodeConfigBundleService = mock(NodeConfigBundleService.class);
    objectMapper = new ObjectMapper();
    mvc =
        MockMvcBuilders.standaloneSetup(
                new InternalNodeController(
                    nodeRegistryService,
                    clientCertReader,
                    new InternalProperties(MTLS_PORT, "./data/internal-tls"),
                    nodeConfigBundleService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor mtlsRequest() {
    return request -> {
      request.setLocalPort(MTLS_PORT);
      request.setRemoteAddr("10.0.0.5");
      return request;
    };
  }

  private OpenVpnNode node(String name) {
    return OpenVpnNode.builder()
        .id("n-1")
        .name(name)
        .mgmtHost("vpn-eu.example.com")
        .mgmtPortBase(7505)
        .enabled(true)
        .createdAt(Instant.now())
        .build();
  }

  @Test
  void registerUpsertsAndReturnsNodeId() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-eu");
    when(nodeRegistryService.upsertByAgent(any(), any(), eq(7505), any(), any(), any()))
        .thenReturn("n-1");
    mvc.perform(
            post("/internal/node/register")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "name",
                            "edge-eu",
                            "mgmtHost",
                            "vpn-eu.example.com",
                            "mgmtPortBase",
                            7505,
                            "adminIp",
                            "10.0.0.5",
                            "mgmtPassword",
                            "mgmt-pass"))))
        .andExpect(jsonPath("$.nodeId").value("n-1"));
    verify(nodeRegistryService)
        .upsertByAgent("edge-eu", "vpn-eu.example.com", 7505, "10.0.0.5", "mgmt-pass", "10.0.0.5");
  }

  @Test
  void registerRejectsRequestOutsideMtlsConnector() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-eu");
    mvc.perform(
            post("/internal/node/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "edge-eu"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("mtls_required"));
  }

  @Test
  void registerRejectsMissingClientCert() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn(null);
    mvc.perform(
            post("/internal/node/register")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "edge-eu"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("client_cert_required"));
  }

  @Test
  void registerRejectsCertForDifferentNode() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-other-node");
    mvc.perform(
            post("/internal/node/register")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "edge-eu"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("cert_identity_mismatch"));
  }

  @Test
  void heartbeatTouchesNode() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-eu");
    when(nodeRegistryService.findNode("n-1")).thenReturn(Optional.of(node("edge-eu")));
    mvc.perform(
            post("/internal/node/heartbeat")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("nodeId", "n-1"))))
        .andExpect(status().isOk());
    verify(nodeRegistryService).heartbeat("n-1", "10.0.0.5");
  }

  @Test
  void heartbeatRejectsCertForDifferentNode() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-us");
    when(nodeRegistryService.findNode("n-1")).thenReturn(Optional.of(node("edge-eu")));
    mvc.perform(
            post("/internal/node/heartbeat")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("nodeId", "n-1"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("cert_identity_mismatch"));
  }

  @Test
  void registerPropagatesBadRequest() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-eu");
    when(nodeRegistryService.upsertByAgent(any(), any(), eq(0), any(), any(), any()))
        .thenThrow(
            new com.opnl.vpn.common.ApiException(
                HttpStatus.BAD_REQUEST,
                "invalid_mgmt_port",
                "Management port base must be 1-65535"));
    mvc.perform(
            post("/internal/node/register")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "name",
                            "edge-eu",
                            "mgmtHost",
                            "host",
                            "mgmtPortBase",
                            0,
                            "mgmtPassword",
                            "mgmt-pass"))))
        .andExpect(status().is4xxClientError())
        .andExpect(jsonPath("$.code").value("invalid_mgmt_port"));
  }

  @Test
  void configReturnsBundleForNode() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-eu");
    when(nodeRegistryService.findNode("n-1")).thenReturn(Optional.of(node("edge-eu")));
    var bundle =
        new NodeConfigBundleService.NodeConfigBundle(
            "abc123",
            java.util.List.of(
                new NodeConfigBundleService.FileEntry("daemon-0.conf", "port 1194\n")),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of());
    when(nodeConfigBundleService.bundleForNode("n-1")).thenReturn(bundle);

    mvc.perform(
            post("/internal/node/config")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("nodeId", "n-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("abc123"))
        .andExpect(jsonPath("$.daemons[0].name").value("daemon-0.conf"));
    verify(nodeRegistryService).checkSourceIp("n-1", "10.0.0.5");
    verify(nodeConfigBundleService).bundleForNode("n-1");
  }

  @Test
  void configRejectsCertForDifferentNode() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-us");
    when(nodeRegistryService.findNode("n-1")).thenReturn(Optional.of(node("edge-eu")));
    mvc.perform(
            post("/internal/node/config")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("nodeId", "n-1"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("cert_identity_mismatch"));
    verify(nodeConfigBundleService, never()).bundleForNode(any());
  }

  @Test
  void configRejectsUnknownNode() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-eu");
    when(nodeRegistryService.findNode("n-1")).thenReturn(Optional.empty());
    mvc.perform(
            post("/internal/node/config")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("nodeId", "n-1"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("node_not_found"));
  }

  @Test
  void configRejectsSourceIpMismatch() throws Exception {
    when(clientCertReader.subjectCn(any())).thenReturn("agent-edge-eu");
    when(nodeRegistryService.findNode("n-1")).thenReturn(Optional.of(node("edge-eu")));
    doThrow(
            new com.opnl.vpn.common.ApiException(
                HttpStatus.FORBIDDEN, "source_ip_mismatch", "Agent source IP not allowed"))
        .when(nodeRegistryService)
        .checkSourceIp(eq("n-1"), any());
    mvc.perform(
            post("/internal/node/config")
                .with(mtlsRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("nodeId", "n-1"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("source_ip_mismatch"));
    verify(nodeConfigBundleService, never()).bundleForNode(any());
  }
}
