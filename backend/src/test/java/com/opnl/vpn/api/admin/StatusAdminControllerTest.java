package com.opnl.vpn.api.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opnl.vpn.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the live status endpoint. */
class StatusAdminControllerTest {

  private StatusAdminService statusAdminService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    statusAdminService = mock(StatusAdminService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new StatusAdminController(statusAdminService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void statusReturnsServerSnapshot() throws Exception {
    ServerStatusDto dto =
        new ServerStatusDto(
            "OpenVPN Panel",
            "0.1.0-SNAPSHOT",
            123,
            2,
            List.of(new ServerStatusDto.DaemonStatus(0, "Primary", 1194, "udp", true, true, true)));
    when(statusAdminService.status()).thenReturn(dto);

    mvc.perform(get("/api/admin/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.brand").value("OpenVPN Panel"))
        .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
        .andExpect(jsonPath("$.uptimeSeconds").value(123))
        .andExpect(jsonPath("$.activeConnections").value(2))
        .andExpect(jsonPath("$.daemons[0].index").value(0))
        .andExpect(jsonPath("$.daemons[0].mgmtReachable").value(true));
  }
}
