package com.passagevpn.api.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.passagevpn.common.GlobalExceptionHandler;
import com.passagevpn.token.ApiToken;
import com.passagevpn.token.ApiTokenService;
import com.passagevpn.token.ApiTokenService.ApiTokenCreated;
import com.passagevpn.user.User;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the API token admin endpoints. */
class ApiTokenAdminControllerTest {

  private ApiTokenService apiTokenService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    apiTokenService = mock(ApiTokenService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new ApiTokenAdminController(apiTokenService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listReturnsTokenDtos() throws Exception {
    when(apiTokenService.list()).thenReturn(List.of(token("t1", "ci-deploy", "ADMIN")));

    mvc.perform(get("/api/admin/api-tokens"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("t1"))
        .andExpect(jsonPath("$[0].label").value("ci-deploy"))
        .andExpect(jsonPath("$[0].prefix").value("passage_abc…"))
        .andExpect(jsonPath("$[0].role").value("ADMIN"))
        .andExpect(jsonPath("$[0].rawToken").doesNotExist());
  }

  @Test
  void createReturnsPlaintextTokenOnce() throws Exception {
    var token = token("t1", "ci-deploy", "ADMIN");
    when(apiTokenService.create("ci-deploy", User.Role.ADMIN, null, "admin"))
        .thenReturn(new ApiTokenCreated(token, "passage_secret"));

    mvc.perform(
            post("/api/admin/api-tokens")
                .principal(new UsernamePasswordAuthenticationToken("admin", null))
                .contentType("application/json")
                .content("{\"label\":\"ci-deploy\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token.id").value("t1"))
        .andExpect(jsonPath("$.token.label").value("ci-deploy"))
        .andExpect(jsonPath("$.rawToken").value("passage_secret"));
  }

  @Test
  void createRejectsMissingLabel() throws Exception {
    mvc.perform(
            post("/api/admin/api-tokens")
                .contentType("application/json")
                .content("{\"label\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void deleteDelegatesToService() throws Exception {
    mvc.perform(delete("/api/admin/api-tokens/t1")).andExpect(status().isOk());
    verify(apiTokenService).delete("t1");
  }

  private static ApiToken token(String id, String label, String role) {
    return ApiToken.builder()
        .id(id)
        .label(label)
        .tokenHash("hash")
        .prefix("passage_abc")
        .role(role)
        .createdBy("admin")
        .createdAt(Instant.now())
        .build();
  }
}
