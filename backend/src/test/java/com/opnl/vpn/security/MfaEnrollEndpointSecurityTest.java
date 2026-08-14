package com.opnl.vpn.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards that the forced-MFA enrollment endpoints are reachable without authentication (permit list
 * in {@link SecurityConfig#PUBLIC_PATHS}). A blank preAuthToken must produce a validation 400, not
 * an unauthenticated 401.
 */
@SpringBootTest(properties = "opnl.jwt.secret=test-context-secret-that-is-at-least-32-bytes")
@AutoConfigureMockMvc
class MfaEnrollEndpointSecurityTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void enrollStartIsPublic() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/mfa/enroll")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preAuthToken\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void enrollConfirmIsPublic() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/mfa/enroll/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preAuthToken\":\"\",\"code\":\"123456\"}"))
        .andExpect(status().isBadRequest());
  }
}
