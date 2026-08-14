package com.opnl.vpn.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.network.NodeRegistryService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the node agent register/heartbeat endpoints. */
class InternalNodeControllerTest {

  private NodeRegistryService nodeRegistryService;
  private MockMvc mvc;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    nodeRegistryService = mock(NodeRegistryService.class);
    objectMapper = new ObjectMapper();
    mvc =
        MockMvcBuilders.standaloneSetup(new InternalNodeController(nodeRegistryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void registerUpsertsAndReturnsNodeId() throws Exception {
    org.mockito.Mockito.when(nodeRegistryService.upsertByAgent(any(), any(), eq(7505), any()))
        .thenReturn("n-1");
    mvc.perform(
            post("/internal/node/register")
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
                            "10.0.0.5"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodeId").value("n-1"));
    verify(nodeRegistryService).upsertByAgent("edge-eu", "vpn-eu.example.com", 7505, "10.0.0.5");
  }

  @Test
  void heartbeatTouchesNode() throws Exception {
    mvc.perform(
            post("/internal/node/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("nodeId", "n-1"))))
        .andExpect(status().isOk());
    verify(nodeRegistryService).heartbeat("n-1");
  }

  @Test
  void registerPropagatesBadRequest() throws Exception {
    org.mockito.Mockito.when(nodeRegistryService.upsertByAgent(any(), any(), eq(0), any()))
        .thenThrow(
            new com.opnl.vpn.common.ApiException(
                HttpStatus.BAD_REQUEST,
                "invalid_mgmt_port",
                "Management port base must be 1-65535"));
    mvc.perform(
            post("/internal/node/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("name", "edge-eu", "mgmtHost", "host", "mgmtPortBase", 0))))
        .andExpect(status().is4xxClientError())
        .andExpect(jsonPath("$.code").value("invalid_mgmt_port"));
  }
}
