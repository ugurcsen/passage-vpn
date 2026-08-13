package com.opnl.vpn.api.portal;

import com.opnl.vpn.profile.ProfileService;
import com.opnl.vpn.profile.ProfileService.OvpnFile;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, token-based profile download (share links). Guarded by the token itself. */
@RestController
@RequestMapping("/api/portal/share")
@Tag(name = "Portal - Share", description = "Public token-based profile downloads")
public class ProfileShareController {

  private final ProfileService profileService;

  public ProfileShareController(ProfileService profileService) {
    this.profileService = profileService;
  }

  @GetMapping("/{token}")
  public OvpnFile download(@PathVariable String token) {
    return profileService.downloadFromToken(token);
  }
}
