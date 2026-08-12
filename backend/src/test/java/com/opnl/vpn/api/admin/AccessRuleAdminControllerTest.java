package com.opnl.vpn.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.access.AccessRule.Action;
import com.opnl.vpn.access.AccessRule.Protocol;
import com.opnl.vpn.access.AccessRule.TargetType;
import com.opnl.vpn.access.AccessRuleDto;
import com.opnl.vpn.access.AccessRuleService;
import com.opnl.vpn.common.GlobalExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer validation tests for the access rule admin API. */
class AccessRuleAdminControllerTest {

  private AccessRuleService ruleService;
  private MockMvc mvc;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    ruleService = mock(AccessRuleService.class);
    objectMapper = new ObjectMapper();
    mvc =
        MockMvcBuilders.standaloneSetup(new AccessRuleAdminController(ruleService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void createRejectsMalformedDstCidr() throws Exception {
    mvc.perform(
            post("/api/admin/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "targetType", "USER",
                            "targetId", "u1",
                            "action", "ALLOW",
                            "protocol", "TCP",
                            "dstCidr", "not-a-cidr",
                            "dstPort", 443))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void createAcceptsValidDstCidr() throws Exception {
    AccessRuleDto returned =
        new AccessRuleDto(
            "r1",
            TargetType.USER,
            "u1",
            null,
            Action.ALLOW,
            Protocol.TCP,
            "192.168.0.0/24",
            null,
            null,
            443,
            true,
            null);
    when(ruleService.create(any())).thenReturn(returned);
    mvc.perform(
            post("/api/admin/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "targetType", "USER",
                            "targetId", "u1",
                            "action", "ALLOW",
                            "protocol", "TCP",
                            "dstCidr", "192.168.0.0/24",
                            "dstPort", 443))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dstCidr").value("192.168.0.0/24"));
  }
}
