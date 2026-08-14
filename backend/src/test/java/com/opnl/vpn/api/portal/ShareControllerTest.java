package com.opnl.vpn.api.portal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.profile.ProfileService;
import com.opnl.vpn.profile.ProfileService.OvpnFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the public token-based .ovpn download endpoint. */
class ShareControllerTest {

  private ProfileService profileService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    profileService = mock(ProfileService.class);
    mvc = MockMvcBuilders.standaloneSetup(new ShareController(profileService)).build();
  }

  @Test
  void servesOvpnAsAttachmentWithProfileContentType() throws Exception {
    when(profileService.downloadFromToken("tok-abc"))
        .thenReturn(new OvpnFile("user-locked-alice.ovpn", "client\nremote vpn.example.com"));

    mvc.perform(get("/share/tok-abc"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/x-openvpn-profile"))
        .andExpect(
            header()
                .string("Content-Disposition", "attachment; filename=\"user-locked-alice.ovpn\""))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().string("client\nremote vpn.example.com"));
  }

  @Test
  void missingTokenReturnsHtmlNotFoundPage() throws Exception {
    when(profileService.downloadFromToken("nope"))
        .thenThrow(ApiException.notFound("token_not_found", "Profile token not found"));

    mvc.perform(get("/share/nope"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("text/html;charset=UTF-8"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Link not found")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Profile token not found")));
  }

  @Test
  void expiredTokenReturnsHtmlErrorPage() throws Exception {
    when(profileService.downloadFromToken("old"))
        .thenThrow(ApiException.conflict("token_expired", "Profile token has expired"));

    mvc.perform(get("/share/old"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType("text/html;charset=UTF-8"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Link expired")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Profile token has expired")));
  }

  @Test
  void htmlEscapesErrorMessage() throws Exception {
    when(profileService.downloadFromToken("x"))
        .thenThrow(
            new ApiException(HttpStatus.NOT_FOUND, "bad", "error <script>alert(1)</script>"));

    mvc.perform(get("/share/x"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("error &lt;script&gt;")));
  }
}
