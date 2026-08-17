package com.passagevpn.api.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.passagevpn.monitor.SystemInfoService;
import com.passagevpn.system.MaintenanceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the maintenance endpoints exposed under /api/admin/system. */
class SystemInfoAdminControllerTest {

  private SystemInfoService systemInfoService;
  private MaintenanceService maintenanceService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    systemInfoService = mock(SystemInfoService.class);
    maintenanceService = mock(MaintenanceService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new SystemInfoAdminController(systemInfoService, maintenanceService))
            .build();
  }

  @Test
  void preflightDelegatesToService() throws Exception {
    when(maintenanceService.preflight())
        .thenReturn(
            new PreflightResult(
                true, List.of(new PreflightCheck("database", PreflightCheck.Status.PASS, "ok"))));

    mvc.perform(post("/api/admin/system/preflight"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(true))
        .andExpect(jsonPath("$.checks[0].name").value("database"));
    verify(maintenanceService).preflight();
  }

  @Test
  void restartBackendDelegatesToService() throws Exception {
    when(maintenanceService.restartBackend())
        .thenReturn(new RestartResult("Backend is restarting"));

    mvc.perform(post("/api/admin/system/restart-backend"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Backend is restarting"));
    verify(maintenanceService).restartBackend();
  }

  @Test
  void reloadDaemonsDelegatesToService() throws Exception {
    when(maintenanceService.reloadDaemons()).thenReturn(new ReloadResult(2, 2, List.of()));

    mvc.perform(post("/api/admin/system/reload-daemons"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signaled").value(2))
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.failed").isEmpty());
    verify(maintenanceService).reloadDaemons();
  }
}
