package com.opnl.vpn.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.DaemonService.DaemonRequest;
import com.opnl.vpn.network.ServerConfig.Protocol;
import com.opnl.vpn.profile.ProfileType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the daemon admin API (security enforced by @PreAuthorize). */
class DaemonAdminControllerTest {

  private DaemonService daemonService;
  private MockMvc mvc;

  private Daemon daemon(int index, String name, int port, boolean clientCertNotRequired) {
    return Daemon.builder()
        .id("d" + index)
        .daemonIndex(index)
        .name(name)
        .port(port)
        .proto(Protocol.udp)
        .subnet("10.8." + index + ".0")
        .subnetMask("255.255.255.0")
        .dnsServers(List.of("1.1.1.1"))
        .fullTunnel(true)
        .clientCertNotRequired(clientCertNotRequired)
        .authUserPass(true)
        .adminHost("vpn.example.com")
        .enabled(true)
        .createdAt(Instant.now())
        .build();
  }

  @BeforeEach
  void setUp() {
    daemonService = mock(DaemonService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new DaemonAdminController(daemonService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listReturnsDaemons() throws Exception {
    when(daemonService.list()).thenReturn(List.of(daemon(0, "Primary", 1194, false)));

    mvc.perform(get("/api/admin/daemons"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].daemonIndex").value(0))
        .andExpect(jsonPath("$[0].name").value("Primary"))
        .andExpect(jsonPath("$[0].primary").value(true))
        .andExpect(jsonPath("$[0].enabled").value(true));
  }

  @Test
  void resolveReturnsServingDaemon() throws Exception {
    when(daemonService.entityForProfile(ProfileType.GENERIC))
        .thenReturn(daemon(1, "Generic", 1195, true));

    mvc.perform(get("/api/admin/daemons/resolve/GENERIC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.port").value(1195))
        .andExpect(jsonPath("$.clientCertNotRequired").value(true));
  }

  @Test
  void createDelegatesToService() throws Exception {
    when(daemonService.create(any(DaemonRequest.class))).thenReturn(daemon(1, "Generic", 1195, true));

    mvc.perform(
            post("/api/admin/daemons")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"daemonIndex\":1,\"name\":\"Generic\",\"port\":1195,\"proto\":\"udp\","
                        + "\"subnet\":\"10.9.0.0\",\"subnetMask\":\"255.255.255.0\","
                        + "\"dnsServers\":[\"1.1.1.1\"],\"fullTunnel\":true,"
                        + "\"clientCertNotRequired\":true,\"authUserPass\":true,\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.daemonIndex").value(1))
        .andExpect(jsonPath("$.name").value("Generic"));

    verify(daemonService).create(any(DaemonRequest.class));
  }

  @Test
  void createRejectsInvalidPayload() throws Exception {
    mvc.perform(
            post("/api/admin/daemons")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"daemonIndex\":99,\"port\":0,\"subnet\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateDelegatesToService() throws Exception {
    when(daemonService.update(anyString(), any(DaemonRequest.class)))
        .thenReturn(daemon(0, "Renamed", 1194, false));

    mvc.perform(
            put("/api/admin/daemons/d0")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"daemonIndex\":0,\"name\":\"Renamed\",\"port\":1194,\"proto\":\"udp\","
                        + "\"subnet\":\"10.8.0.0\",\"subnetMask\":\"255.255.255.0\","
                        + "\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
  }

  @Test
  void deleteDelegatesToService() throws Exception {
    mvc.perform(delete("/api/admin/daemons/d1")).andExpect(status().isOk());
    verify(daemonService).delete("d1");
  }

  @Test
  void toggleEnabledDelegatesToService() throws Exception {
    Daemon disabled = daemon(1, "Generic", 1195, true);
    disabled.setEnabled(false);
    when(daemonService.setEnabled("d1", false)).thenReturn(disabled);

    mvc.perform(post("/api/admin/daemons/d1/enabled").param("enabled", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));
  }
}
