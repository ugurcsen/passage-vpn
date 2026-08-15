package com.opnl.vpn.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opnl.vpn.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards that the setup state machine stays reachable while the network configuration is only
 * readable by admins (the endpoint exposes server subnet, ports, DNS and admin host).
 */
@SpringBootTest(
    properties = {
      "opnl.jwt.secret=test-context-secret-that-is-at-least-32-bytes",
      "opnl.internal-token=test-internal-token",
      "opnl.openvpn.mgmt-password=test-mgmt-pass"
    })
@AutoConfigureMockMvc
class SetupControllerSecurityTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;

  @Test
  void stateIsPublic() throws Exception {
    mockMvc.perform(get("/api/setup/state")).andExpect(status().isOk());
  }

  @Test
  void serverConfigRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/setup/server-config")).andExpect(status().isForbidden());
  }

  @Test
  void serverConfigIsDeniedToNonAdminRole() throws Exception {
    String token =
        jwtService.issueAccessToken("u-user", "alice", com.opnl.vpn.user.User.Role.USER.name());
    mockMvc
        .perform(get("/api/setup/server-config").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void serverConfigIsAllowedForAdmin() throws Exception {
    String token =
        jwtService.issueAccessToken("u-admin", "admin", com.opnl.vpn.user.User.Role.ADMIN.name());
    mockMvc
        .perform(get("/api/setup/server-config").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }
}
