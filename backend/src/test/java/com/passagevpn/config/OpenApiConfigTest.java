package com.passagevpn.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the generated OpenAPI spec exposes the JWT bearer scheme so Swagger UI shows an
 * "Authorize" button for authenticated testing.
 */
@SpringBootTest(
    properties = {
      "passage.jwt.secret=test-context-secret-that-is-at-least-32-bytes",
      "passage.internal-token=test-internal-token",
      "passage.openvpn.mgmt-password=test-mgmt-pass"
    })
@AutoConfigureMockMvc
class OpenApiConfigTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void exposesBearerAuthSecurityScheme() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
  }

  @Test
  void appliesGlobalSecurityRequirement() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.security[0].bearerAuth").exists());
  }
}
