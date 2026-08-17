package com.passagevpn.api.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.passagevpn.common.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the config report endpoint. */
class ConfigReportAdminControllerTest {

  private ConfigReportService configReportService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    configReportService = mock(ConfigReportService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new ConfigReportAdminController(configReportService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void returnsReportPayload() throws Exception {
    when(configReportService.report())
        .thenReturn(
            new ConfigReportDto(
                "OpenVPN Panel",
                "0.1.0",
                Instant.now().toString(),
                "sqlite",
                new ConfigReportDto.DataDirs("./pki", "./ccd", "./config", "./logs"),
                Map.of("network_mode", "nat"),
                List.of(new ConfigReportDto.DaemonSummary(0, "Primary", 1194, "udp", true)),
                new ConfigReportDto.PkiInventory(5, 3, 1, 1, 0),
                12,
                2));

    mvc.perform(get("/api/admin/config-report"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.brand").value("OpenVPN Panel"))
        .andExpect(jsonPath("$.dbType").value("sqlite"))
        .andExpect(jsonPath("$.serverSettings.network_mode").value("nat"))
        .andExpect(jsonPath("$.daemons[0].name").value("Primary"))
        .andExpect(jsonPath("$.pki.total").value(5))
        .andExpect(jsonPath("$.users").value(12));
  }
}
