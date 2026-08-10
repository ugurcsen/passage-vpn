package com.opnl.vpn.profile;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.pki.CertService;
import com.opnl.vpn.pki.EasyRsaService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Generates connection profiles and manages shareable download tokens. */
@Service
public class ProfileService {

  public record OvpnFile(String filename, String content) {}

  /** Short-lived single-use token used to build the QR-code sharing payload. */
  public record QrPayload(String token, Instant expiresAt) {}

  private final OvpnGenerator generator;
  private final CertService certService;
  private final EasyRsaService easyRsa;
  private final DaemonService daemonService;
  private final UserRepository userRepository;
  private final ProfileTokenRepository tokenRepository;
  private final OpnlProperties properties;

  public ProfileService(
      OvpnGenerator generator,
      CertService certService,
      EasyRsaService easyRsa,
      DaemonService daemonService,
      UserRepository userRepository,
      ProfileTokenRepository tokenRepository,
      OpnlProperties properties) {
    this.generator = generator;
    this.certService = certService;
    this.easyRsa = easyRsa;
    this.daemonService = daemonService;
    this.userRepository = userRepository;
    this.tokenRepository = tokenRepository;
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
    ProfileToken token = createToken(userId, type, Instant.now().plus(Duration.ofHours(1)), 1);
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

  private User requireUserForGeneric() {
    return userRepository.findAll().stream()
        .filter(u -> u.getRole() == User.Role.USER || u.getRole() == User.Role.RESELLER)
        .findFirst()
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
            certMaterial == null ? null : certMaterial[1]);
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
    return userRepository
        .findById(userId)
        .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
  }
}
