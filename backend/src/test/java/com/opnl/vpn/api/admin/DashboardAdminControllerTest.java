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

/** Web-layer tests for the dashboard endpoint. */
class DashboardAdminControllerTest {

  private DashboardAdminService dashboardAdminService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    dashboardAdminService = mock(DashboardAdminService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new DashboardAdminController(dashboardAdminService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void dashboardReturnsCountsAndRecentConnections() throws Exception {
    DashboardDto dto = new DashboardDto(5, 2, 4, 1, 3, 3, List.of());
    when(dashboardAdminService.dashboard()).thenReturn(dto);

    mvc.perform(get("/api/admin/dashboard"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users").value(5))
        .andExpect(jsonPath("$.groups").value(2))
        .andExpect(jsonPath("$.activeCertificates").value(4))
        .andExpect(jsonPath("$.activeConnections").value(1))
        .andExpect(jsonPath("$.runningDaemons").value(3))
        .andExpect(jsonPath("$.totalDaemons").value(3))
        .andExpect(jsonPath("$.recentConnections").isArray());
  }
}
