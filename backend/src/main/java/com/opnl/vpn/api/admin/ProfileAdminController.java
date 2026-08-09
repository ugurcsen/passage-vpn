package com.opnl.vpn.api.admin;

import com.opnl.vpn.profile.ProfileService;
import com.opnl.vpn.profile.ProfileService.OvpnFile;
import com.opnl.vpn.profile.ProfileToken;
import com.opnl.vpn.profile.ProfileTokenRepository;
import com.opnl.vpn.profile.ProfileType;
import com.opnl.vpn.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin profile management: download any user's profile and manage sharing tokens. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class ProfileAdminController {

  private final ProfileService profileService;
  private final ProfileTokenRepository tokenRepository;
  private final UserRepository userRepository;

  public ProfileAdminController(
      ProfileService profileService,
      ProfileTokenRepository tokenRepository,
      UserRepository userRepository) {
    this.profileService = profileService;
    this.tokenRepository = tokenRepository;
    this.userRepository = userRepository;
  }

  @GetMapping("/users/{userId}/profiles/{type}/download")
  public OvpnFile download(@PathVariable String userId, @PathVariable ProfileType type) {
    return profileService.downloadForUser(userId, type);
  }

  @PostMapping("/profile-tokens")
  public ProfileTokenDto create(@Valid @RequestBody CreateTokenRequest request) {
    ProfileToken token =
        profileService.createToken(
            request.userId(), request.profileType(), request.expiresAt(), request.usesLeft());
    return ProfileTokenDto.from(token, usernameFor(token.getUserId()));
  }

  @GetMapping("/profile-tokens")
  public List<ProfileTokenDto> listTokens() {
    Map<String, String> usernames =
        userRepository.findAll().stream()
            .collect(Collectors.toMap(u -> u.getId(), u -> u.getUsername(), (a, b) -> a));
    return tokenRepository.findAll().stream()
        .sorted(java.util.Comparator.comparing(ProfileToken::getCreatedAt).reversed())
        .map(t -> ProfileTokenDto.from(t, usernames.get(t.getUserId())))
        .toList();
  }

  @PostMapping("/profile-tokens/{id}/revoke")
  public void revoke(@PathVariable String id) {
    profileService.revokeToken(id);
  }

  private String usernameFor(String userId) {
    return userId == null
        ? null
        : userRepository.findById(userId).map(u -> u.getUsername()).orElse(null);
  }

  public record CreateTokenRequest(
      String userId, @NotNull ProfileType profileType, Instant expiresAt, Integer usesLeft) {}
}
