package com.opnl.vpn.api.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.monitor.MgmtClientManager;
import com.opnl.vpn.network.ConnectionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the admin connections endpoint (kill session). */
class ConnectionAdminControllerTest {

  private MgmtClientManager mgmtClientManager;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mgmtClientManager = mock(MgmtClientManager.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ConnectionAdminController(new ConnectionRegistry(), mgmtClientManager))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void disconnectDelegatesToManagementKill() throws Exception {
    when(mgmtClientManager.kill("alice")).thenReturn(true);

    mvc.perform(post("/api/admin/connections/alice/disconnect")).andExpect(status().isOk());
  }

  @Test
  void disconnectReturns404WhenNoSession() throws Exception {
    when(mgmtClientManager.kill("ghost")).thenReturn(false);

    mvc.perform(post("/api/admin/connections/ghost/disconnect"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("session_not_found"));
  }
}
