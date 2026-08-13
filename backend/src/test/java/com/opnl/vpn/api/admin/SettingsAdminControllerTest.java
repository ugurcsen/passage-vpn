package com.opnl.vpn.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.setting.SettingsService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the server settings admin API. */
class SettingsAdminControllerTest {

  private SettingsService settingsService;
  private DaemonService daemonService;
  private MockMvc mvc;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    settingsService = mock(SettingsService.class);
    daemonService = mock(DaemonService.class);
    objectMapper = new ObjectMapper();
    mvc =
        MockMvcBuilders.standaloneSetup(
                new SettingsAdminController(
                    settingsService, daemonService, mock(AuditLogService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listReturnsServerSettings() throws Exception {
    when(settingsService.serverSettings()).thenReturn(Map.of("brand", "MyPanel", "max_conn", 3));

    mvc.perform(get("/api/admin/settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.brand").value("MyPanel"))
        .andExpect(jsonPath("$.max_conn").value(3));
  }

  @Test
  void putStoresSettingAndReturnsUpdatedMap() throws Exception {
    when(settingsService.serverSettings()).thenReturn(Map.of("brand", "MyPanel"));

    mvc.perform(
            put("/api/admin/settings/brand")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("value", "MyPanel"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.brand").value("MyPanel"));

    verify(settingsService).setServerSetting("brand", "MyPanel");
  }

  @Test
  void putRejectsMalformedKey() throws Exception {
    mvc.perform(
            put("/api/admin/settings/bad key!")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("value", "x"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_setting_key"));

    verify(settingsService, never()).setServerSetting(any(), any());
  }

  @Test
  void putRejectsNullValue() throws Exception {
    mvc.perform(
            put("/api/admin/settings/brand")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": null}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));

    verify(settingsService, never()).setServerSetting(any(), any());
  }

  @Test
  void putNetworkModeTriggersConfigRewrite() throws Exception {
    when(settingsService.serverSettings()).thenReturn(Map.of("network_mode", "routed"));

    mvc.perform(
            put("/api/admin/settings/network_mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("value", "routed"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.network_mode").value("routed"));

    verify(settingsService).setServerSetting("network_mode", "routed");
    verify(daemonService).writeAll();
  }

  @Test
  void putNetworkModeRejectsInvalidValue() throws Exception {
    mvc.perform(
            put("/api/admin/settings/network_mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("value", "bridged"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_network_mode"));

    verify(settingsService, never()).setServerSetting(any(), any());
    verify(daemonService, never()).writeAll();
  }

  @Test
  void putUnrelatedSettingDoesNotRewriteConfigs() throws Exception {
    when(settingsService.serverSettings()).thenReturn(Map.of("brand", "MyPanel"));

    mvc.perform(
            put("/api/admin/settings/brand")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("value", "MyPanel"))))
        .andExpect(status().isOk());

    verify(settingsService).setServerSetting("brand", "MyPanel");
    verify(daemonService, never()).writeAll();
  }

  @Test
  void deleteNetworkModeRestoresNatAndRewritesConfigs() throws Exception {
    mvc.perform(delete("/api/admin/settings/network_mode")).andExpect(status().isNoContent());

    verify(settingsService).deleteServerSetting("network_mode");
    verify(daemonService).writeAll();
  }

  @Test
  void deleteRemovesSetting() throws Exception {
    mvc.perform(delete("/api/admin/settings/brand")).andExpect(status().isNoContent());

    verify(settingsService).deleteServerSetting("brand");
  }
}
