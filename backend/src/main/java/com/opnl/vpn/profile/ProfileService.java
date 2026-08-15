package com.opnl.vpn.profile;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.pki.CertService;
import com.opnl.vpn.pki.EasyRsaService;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Generates connection profiles and manages shareable download tokens. */
@Service
public class ProfileService {

  public record OvpnFile(String filename, String content) {}

  /** Short-lived single-use token used to build the QR-code sharing payload. */
  public record QrPayload(String token, Instant expiresAt) {}

  /** Validity of a QR share link. The backend enforces expiry on download (409 after this). */
  public static final Duration QR_TOKEN_TTL = Duration.ofMinutes(5);

  private final OvpnGenerator generator;
  private final CertService certService;
  private final EasyRsaService easyRsa;
  private final DaemonService daemonService;
  private final UserRepository userRepository;
  private final ProfileTokenRepository tokenRepository;
  private final SettingsService settingsService;
  private final OpnlProperties properties;

  public ProfileService(
      OvpnGenerator generator,
      CertService certService,
      EasyRsaService easyRsa,
      DaemonService daemonService,
      UserRepository userRepository,
      ProfileTokenRepository tokenRepository,
      SettingsService settingsService,
      OpnlProperties properties) {
    this.generator = generator;
    this.certService = certService;
    this.easyRsa = easyRsa;
    this.daemonService = daemonService;
    this.userRepository = userRepository;
    this.tokenRepository = tokenRepository;
    this.settingsService = settingsService;
    this.properties = properties;
  }

  /** Downloads a profile for a user; locked types ensure a client certificate exists. */
  @Transactional
  public OvpnFile downloadForUser(String userId, ProfileType type) {
    User user = requireUser(userId);
    return build(user, type, certMaterial(user, type));
  }

  /** Resolves and consumes a sharing token; returns the profile it points to. */
  @Transactional
  public OvpnFile downloadFromToken(String rawToken) {
    ProfileToken token =
        tokenRepository
            .findByToken(rawToken)
            .orElseThrow(() -> ApiException.notFound("token_not_found", "Profile token not found"));
    if (token.isRevoked()) {
      throw ApiException.conflict("token_revoked", "Profile token has been revoked");
    }
    if (token.getExpiresAt() != null && Instant.now().isAfter(token.getExpiresAt())) {
      throw ApiException.conflict("token_expired", "Profile token has expired");
    }
    if (token.getUsesLeft() != null && token.getUsesLeft() <= 0) {
      throw ApiException.conflict("token_exhausted", "Profile token has no uses left");
    }

    if (token.getUsesLeft() != null) {
      token.setUsesLeft(token.getUsesLeft() - 1);
      tokenRepository.save(token);
    }

    if (token.getUserId() != null) {
      User user = requireUser(token.getUserId());
      return build(user, token.getProfileType(), certMaterial(user, token.getProfileType()));
    }
    User generic = requireUserForGeneric();
    return build(generic, token.getProfileType(), null);
  }

  /** Creates a short-lived single-use token for QR-code sharing of the user's own profile. */
  @Transactional
  public QrPayload createQrPayload(String userId, ProfileType type) {
    ProfileToken token = createToken(userId, type, Instant.now().plus(QR_TOKEN_TTL), 1);
    return new QrPayload(token.getToken(), token.getExpiresAt());
  }

  @Transactional
  public ProfileToken createToken(
      String userId, ProfileType type, Instant expiresAt, Integer usesLeft) {
    if (type == ProfileType.GENERIC) {
      requireUserForGeneric();
    } else {
      requireUser(userId);
    }
    ProfileToken token =
        ProfileToken.builder()
            .id(UUID.randomUUID().toString())
            .token(UUID.randomUUID().toString().replace("-", ""))
            .userId(type == ProfileType.GENERIC ? null : userId)
            .profileType(type)
            .expiresAt(expiresAt)
            .usesLeft(usesLeft)
            .createdAt(Instant.now())
            .build();
    return tokenRepository.save(token);
  }

  @Transactional(readOnly = true)
  public List<ProfileToken> listTokens() {
    return tokenRepository.findAll().stream()
        .sorted(java.util.Comparator.comparing(ProfileToken::getCreatedAt).reversed())
        .toList();
  }

  @Transactional
  public void revokeToken(String id) {
    ProfileToken token =
        tokenRepository
            .findById(id)
            .orElseThrow(() -> ApiException.notFound("token_not_found", "Profile token not found"));
    token.setRevoked(true);
    tokenRepository.save(token);
  }

  @Transactional(readOnly = true)
  public Optional<ProfileToken> findToken(String token) {
    return tokenRepository.findByToken(token);
  }

  /**
   * Profile types allowed for portal self-service downloads. Reads the {@code portal_profile_types}
   * server setting; when unset (or empty) only the password-bound certificate types are allowed so
   * AUTO_LOGIN/GENERIC stay disabled unless an admin opts in.
   */
  @Transactional(readOnly = true)
  public Set<ProfileType> portalAllowedTypes() {
    Object value = settingsService.serverSettings().get(SettingKeys.PORTAL_PROFILE_TYPES);
    Set<ProfileType> allowed = EnumSet.noneOf(ProfileType.class);
    if (value instanceof java.util.Collection<?> collection) {
      for (Object item : collection) {
        profileTypeOf(item).ifPresent(allowed::add);
      }
    } else if (value instanceof String s && !s.isBlank()) {
      for (String name : s.split(",")) {
        profileTypeOf(name).ifPresent(allowed::add);
      }
    }
    return allowed.isEmpty()
        ? EnumSet.of(ProfileType.USER_LOCKED, ProfileType.SERVER_LOCKED)
        : allowed;
  }

  /** Whether the given profile type may be downloaded from the client portal. */
  public boolean portalAllows(ProfileType type) {
    return portalAllowedTypes().contains(type);
  }

  /**
   * Whether the given profile type is actually usable right now: a matching enabled daemon exists
   * (and for GENERIC a non-admin account is present to back the shared credentials).
   */
  @Transactional(readOnly = true)
  public boolean portalAvailable(ProfileType type) {
    if (!daemonService.findMatchingForProfile(type).isPresent()) {
      return false;
    }
    return type != ProfileType.GENERIC || requireUserForGenericSafe().isPresent();
  }

  /** Rejects a portal download/QR for types the admin disabled or no daemon can serve. */
  public void assertPortalDownloadAllowed(ProfileType type) {
    if (!portalAllows(type)) {
      throw ApiException.forbidden(
          "portal_type_disabled",
          "This profile type is disabled by the administrator for self-service downloads");
    }
    if (!portalAvailable(type)) {
      throw ApiException.forbidden(
          "portal_type_unavailable",
          "This profile type is unavailable; no enabled daemon serves it");
    }
  }

  private Optional<ProfileType> profileTypeOf(Object name) {
    if (name == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(ProfileType.valueOf(String.valueOf(name).trim()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private Optional<User> requireUserForGenericSafe() {
    return userRepository.findAll().stream()
        .filter(u -> u.getRole() == User.Role.USER || u.getRole() == User.Role.RESELLER)
        .findFirst();
  }

  private User requireUserForGeneric() {
    return requireUserForGenericSafe()
        .orElseThrow(
            () ->
                ApiException.badRequest(
                    "no_user", "A non-admin user is required for generic profiles"));
  }

  private String[] certMaterial(User user, ProfileType type) {
    if (type == ProfileType.GENERIC) {
      return null;
    }
    certService.ensureUserCert(user.getId());
    return new String[] {
      easyRsa.clientCert(user.getUsername()), easyRsa.clientKey(user.getUsername())
    };
  }

  private OvpnFile build(User user, ProfileType type, String[] certMaterial) {
    ServerConfig config = daemonService.resolveForProfile(type);
    String adminHost = properties.openvpn().adminHost();
    String content =
        generator.render(
            type,
            config,
            adminHost,
            easyRsa.caCert(),
            easyRsa.taKey(),
            certMaterial == null ? null : certMaterial[0],
            certMaterial == null ? null : certMaterial[1],
            requiresMfaChallenge(user, type));
    String daemonSuffix = type == ProfileType.GENERIC ? "-generic" : "";
    String filename =
        type.name().toLowerCase().replace('_', '-')
            + daemonSuffix
            + "-"
            + user.getUsername().replaceAll("[^A-Za-z0-9_.-]", "_")
            + ".ovpn";
    return new OvpnFile(filename, content);
  }

  private User requireUser(String userId) {
    if (userId == null || userId.isBlank()) {
      throw ApiException.badRequest("user_required", "User id is required");
    }
    return userRepository
        .findById(userId)
        .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
  }

  /**
   * Decides whether a profile must prompt for a TOTP code at connect time. AUTO_LOGIN is
   * certificate-only and never prompts. USER_LOCKED follows the owning user's MFA state (or the
   * server-wide require-mfa-on-connect policy); SERVER_LOCKED/GENERIC profiles are not bound to a
   * single account, so only the server policy applies.
   */
  private boolean requiresMfaChallenge(User user, ProfileType type) {
    if (type == ProfileType.AUTO_LOGIN) {
      return false;
    }
    boolean serverPolicy =
        Boolean.TRUE.equals(
            settingsService.serverSettings().get(SettingKeys.REQUIRE_MFA_ON_CONNECT));
    return type == ProfileType.USER_LOCKED ? user.isMfaEnabled() || serverPolicy : serverPolicy;
  }
}
