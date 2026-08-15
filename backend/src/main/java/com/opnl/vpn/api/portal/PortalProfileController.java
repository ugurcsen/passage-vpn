package com.opnl.vpn.api.portal;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.profile.ProfileService;
import com.opnl.vpn.profile.ProfileService.OvpnFile;
import com.opnl.vpn.profile.ProfileType;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Client self-service portal: list and download own connection profiles. */
@RestController
@RequestMapping("/api/portal/profiles")
@Tag(name = "Portal - Profiles", description = "Self-service profile downloads")
public class PortalProfileController {

  private final ProfileService profileService;
  private final DaemonService daemonService;

  public PortalProfileController(ProfileService profileService, DaemonService daemonService) {
    this.profileService = profileService;
    this.daemonService = daemonService;
  }

  /**
   * Lists every profile type with its availability. {@code available} is false when the admin
   * policy disabled the type or no enabled daemon can serve it; the UI hides those cards.
   */
  @GetMapping
  public List<ProfileTypeDto> list() {
    return Arrays.stream(ProfileType.values())
        .map(
            type -> ProfileTypeDto.from(type, profileService.portalAllows(type), isAvailable(type)))
        .toList();
  }

  @GetMapping("/{type}/download")
  public OvpnFile download(@PathVariable ProfileType type, Authentication authentication) {
    profileService.assertPortalDownloadAllowed(type);
    String userId = principal(authentication);
    return profileService.downloadForUser(userId, type);
  }

  @GetMapping("/{type}/qr")
  public ProfileService.QrPayload qr(
      @PathVariable ProfileType type, Authentication authentication) {
    profileService.assertPortalDownloadAllowed(type);
    return profileService.createQrPayload(principal(authentication), type);
  }

  /** Whether a type is served by a matching daemon, independent of the admin policy allow-list. */
  private boolean isAvailable(ProfileType type) {
    return daemonService.findMatchingForProfile(type).isPresent();
  }

  private String principal(Authentication authentication) {
    if (authentication == null || authentication.getName() == null) {
      throw ApiException.unauthorized("unauthorized", "Authentication required");
    }
    return authentication.getName();
  }

  public record ProfileTypeDto(
      ProfileType type, String label, boolean locked, boolean allowed, boolean available) {
    static ProfileTypeDto from(ProfileType type, boolean allowed, boolean available) {
      String label =
          switch (type) {
            case USER_LOCKED -> "User-locked (username/password + certificate)";
            case AUTO_LOGIN -> "Auto-login (certificate only)";
            case SERVER_LOCKED -> "Server-locked (certificate + password)";
            case GENERIC -> "Generic (username/password only)";
          };
      return new ProfileTypeDto(type, label, type != ProfileType.GENERIC, allowed, available);
    }
  }
}
