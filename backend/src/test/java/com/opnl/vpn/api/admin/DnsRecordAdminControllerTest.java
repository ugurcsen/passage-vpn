package com.opnl.vpn.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.dns.DnsOverrideService;
import com.opnl.vpn.dns.DnsRecord.Scope;
import com.opnl.vpn.dns.DnsRecordDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer validation tests for the DNS override admin API. */
class DnsRecordAdminControllerTest {

  private DnsOverrideService dnsOverrideService;
  private MockMvc mvc;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    dnsOverrideService = mock(DnsOverrideService.class);
    objectMapper = new ObjectMapper();
    mvc =
        MockMvcBuilders.standaloneSetup(new DnsRecordAdminController(dnsOverrideService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listReturnsRecords() throws Exception {
    when(dnsOverrideService.list())
        .thenReturn(
            List.of(
                new DnsRecordDto(
                    "d1", "git.internal", "10.10.0.5", Scope.GLOBAL, null, null, true, null)));
    mvc.perform(get("/api/admin/dns-overrides"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].hostname").value("git.internal"))
        .andExpect(jsonPath("$[0].ipv4").value("10.10.0.5"));
  }

  @Test
  void createRejectsMalformedHostname() throws Exception {
    mvc.perform(
            post("/api/admin/dns-overrides")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "hostname", "Bad Hostname!",
                            "ipv4", "10.10.0.5",
                            "scope", "GLOBAL"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void createRejectsMalformedIpv4() throws Exception {
    mvc.perform(
            post("/api/admin/dns-overrides")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "hostname", "git.internal",
                            "ipv4", "999.10.0.5",
                            "scope", "GLOBAL"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void createRejectsScopedRecordWithoutScopeId() throws Exception {
    mvc.perform(
            post("/api/admin/dns-overrides")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "hostname", "git.internal",
                            "ipv4", "10.10.0.5",
                            "scope", "USER"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void createAcceptsValidGlobalRecord() throws Exception {
    DnsRecordDto returned =
        new DnsRecordDto("d1", "git.internal", "10.10.0.5", Scope.GLOBAL, null, null, true, null);
    when(dnsOverrideService.create(any())).thenReturn(returned);
    mvc.perform(
            post("/api/admin/dns-overrides")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "hostname", "git.internal",
                            "ipv4", "10.10.0.5",
                            "scope", "GLOBAL"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hostname").value("git.internal"));
  }

  @Test
  void updateCallsServiceWithId() throws Exception {
    DnsRecordDto returned =
        new DnsRecordDto("d1", "git.internal", "10.10.0.6", Scope.GLOBAL, null, null, true, null);
    when(dnsOverrideService.update(eq("d1"), any())).thenReturn(returned);
    mvc.perform(
            put("/api/admin/dns-overrides/d1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "hostname", "git.internal",
                            "ipv4", "10.10.0.6",
                            "scope", "GLOBAL"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ipv4").value("10.10.0.6"));
  }

  @Test
  void deleteDelegatesToService() throws Exception {
    mvc.perform(delete("/api/admin/dns-overrides/d1")).andExpect(status().isOk());
  }

  @Test
  void setEnabledDelegatesToService() throws Exception {
    DnsRecordDto returned =
        new DnsRecordDto("d1", "git.internal", "10.10.0.5", Scope.GLOBAL, null, null, false, null);
    when(dnsOverrideService.setEnabled("d1", false)).thenReturn(returned);
    mvc.perform(
            post("/api/admin/dns-overrides/d1/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));
  }
}
