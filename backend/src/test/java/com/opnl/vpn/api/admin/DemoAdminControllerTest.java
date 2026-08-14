package com.opnl.vpn.api.admin;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.system.DemoSeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the admin demo seeding endpoint. */
class DemoAdminControllerTest {

  private DemoSeedService demoSeedService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    demoSeedService = mock(DemoSeedService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new DemoAdminController(demoSeedService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void seedReturnsUserCount() throws Exception {
    when(demoSeedService.seed(false)).thenReturn(4);
    mvc.perform(post("/api/admin/demo/seed").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users").value(4));
    verify(demoSeedService).seed(false);
  }

  @Test
  void seedForwardsForceFlag() throws Exception {
    when(demoSeedService.seed(true)).thenReturn(4);
    mvc.perform(
            post("/api/admin/demo/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"force\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users").value(4));
    verify(demoSeedService).seed(true);
  }

  @Test
  void seedDefaultsToNonForceWithoutBody() throws Exception {
    when(demoSeedService.seed(anyBoolean())).thenReturn(4);
    mvc.perform(post("/api/admin/demo/seed"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users").value(4));
    verify(demoSeedService).seed(false);
  }

  @Test
  void seedPropagatesServiceError() throws Exception {
    when(demoSeedService.seed(false))
        .thenThrow(
            new com.opnl.vpn.common.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "demo_seeded", "Already loaded"));
    mvc.perform(post("/api/admin/demo/seed").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("demo_seeded"));
  }
}
