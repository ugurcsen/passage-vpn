package com.opnl.vpn.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.common.GlobalExceptionHandler;
import com.opnl.vpn.profile.ProfileService;
import com.opnl.vpn.profile.ProfileToken;
import com.opnl.vpn.profile.ProfileTokenRepository;
import com.opnl.vpn.profile.ProfileType;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer validation tests for profile-token creation. */
class ProfileAdminControllerTest {

  private ProfileService profileService;
  private ProfileTokenRepository tokenRepository;
  private UserRepository userRepository;
  private MockMvc mvc;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    profileService = mock(ProfileService.class);
    tokenRepository = mock(ProfileTokenRepository.class);
    userRepository = mock(UserRepository.class);
    objectMapper = new ObjectMapper();
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ProfileAdminController(profileService, tokenRepository, userRepository))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void createRejectsMissingUserIdForNonGenericType() throws Exception {
    mvc.perform(
            post("/api/admin/profile-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("profileType", "USER_LOCKED"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
    verify(profileService, never()).createToken(any(), any(), any(), any(), any());
  }

  @Test
  void createAcceptsGenericWithoutUserId() throws Exception {
    ProfileToken token = token(ProfileType.GENERIC, null);
    when(profileService.createToken(eq(null), eq(ProfileType.GENERIC), any(), any(), any()))
        .thenReturn(token);
    mvc.perform(
            post("/api/admin/profile-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("profileType", "GENERIC"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileType").value("GENERIC"));
  }

  @Test
  void createWithUnknownUserIdReturns404() throws Exception {
    when(profileService.createToken(
            eq("unknown-id"), eq(ProfileType.USER_LOCKED), any(), any(), any()))
        .thenThrow(ApiException.notFound("user_not_found", "User not found"));
    mvc.perform(
            post("/api/admin/profile-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "userId", "unknown-id",
                            "profileType", "USER_LOCKED"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("user_not_found"));
  }

  @Test
  void createPinsTokenToDaemonWhenRequested() throws Exception {
    ProfileToken token = token(ProfileType.USER_LOCKED, "u1");
    when(profileService.createToken(eq("u1"), eq(ProfileType.USER_LOCKED), any(), any(), eq(1)))
        .thenReturn(token);
    mvc.perform(
            post("/api/admin/profile-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "userId", "u1",
                            "profileType", "USER_LOCKED",
                            "daemonIndex", 1))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileType").value("USER_LOCKED"));
  }

  @Test
  void createRejectsZeroUses() throws Exception {
    mvc.perform(
            post("/api/admin/profile-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "userId", "u1",
                            "profileType", "USER_LOCKED",
                            "usesLeft", 0))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
    verify(profileService, never()).createToken(any(), any(), any(), any(), any());
  }

  private ProfileToken token(ProfileType type, String userId) {
    return ProfileToken.builder()
        .id("t1")
        .token("abc")
        .userId(userId)
        .profileType(type)
        .expiresAt(Instant.now().plusSeconds(3600))
        .usesLeft(1)
        .createdAt(Instant.now())
        .revoked(false)
        .build();
  }
}
