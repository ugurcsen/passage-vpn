package com.opnl.vpn.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.pki.CertService;
import com.opnl.vpn.pki.EasyRsaService;
import com.opnl.vpn.profile.ProfileService.OvpnFile;
import com.opnl.vpn.setup.SetupService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileServiceTest {

  private CertService certService;
  private EasyRsaService easyRsa;
  private SetupService setupService;
  private UserRepository userRepository;
  private ProfileTokenRepository tokenRepository;
  private ProfileService service;

  private User alice() {
    return User.builder()
        .id("u1")
        .username("alice")
        .role(User.Role.USER)
        .createdAt(Instant.now())
        .build();
  }

  @BeforeEach
  void setUp() {
    certService = mock(CertService.class);
    easyRsa = mock(EasyRsaService.class);
    setupService = mock(SetupService.class);
    userRepository = mock(UserRepository.class);
    tokenRepository = mock(ProfileTokenRepository.class);
    service =
        new ProfileService(
            new OvpnGenerator(),
            certService,
            easyRsa,
            setupService,
            userRepository,
            tokenRepository);

    ServerConfig config = ServerConfig.defaults();
    when(setupService.currentServerConfig()).thenReturn(config);
    when(easyRsa.caCert()).thenReturn("CA-CERT");
    when(easyRsa.taKey()).thenReturn("TA-KEY");
    when(easyRsa.clientCert("alice")).thenReturn("CERT");
    when(easyRsa.clientKey("alice")).thenReturn("KEY");
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice()));
  }

  @Test
  void userLockedProfileEmbedsCertificateAndPromptsForPassword() {
    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.filename()).isEqualTo("user-locked-alice.ovpn");
    assertThat(file.content())
        .contains("auth-user-pass")
        .contains("<ca>\nCA-CERT")
        .contains("<cert>\nCERT")
        .contains("<key>\nKEY")
        .contains("remote vpn.example.com 1194")
        .contains("proto udp");
  }

  @Test
  void autoLoginProfileOmitsPasswordPrompt() {
    OvpnFile file = service.downloadForUser("u1", ProfileType.AUTO_LOGIN);

    assertThat(file.content()).doesNotContain("auth-user-pass").contains("<cert>\nCERT");
  }

  @Test
  void genericProfileHasNoCertificate() {
    OvpnFile file = service.downloadForUser("u1", ProfileType.GENERIC);

    assertThat(file.content())
        .contains("auth-user-pass")
        .contains("<ca>\nCA-CERT")
        .doesNotContain("<cert>")
        .doesNotContain("<key>");
  }

  @Test
  void tokenDownloadConsumesUse() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok-abc")
            .userId("u1")
            .profileType(ProfileType.USER_LOCKED)
            .usesLeft(3)
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByToken("tok-abc")).thenReturn(Optional.of(token));
    when(tokenRepository.save(token)).thenReturn(token);

    OvpnFile file = service.downloadFromToken("tok-abc");

    assertThat(file.content()).contains("remote vpn.example.com 1194");
    assertThat(token.getUsesLeft()).isEqualTo(2);
  }

  @Test
  void revokedTokenIsRejected() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok-abc")
            .profileType(ProfileType.USER_LOCKED)
            .revoked(true)
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByToken("tok-abc")).thenReturn(Optional.of(token));

    assertThatThrownBy(() -> service.downloadFromToken("tok-abc"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "token_revoked");
  }

  @Test
  void exhaustedTokenIsRejected() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok-abc")
            .userId("u1")
            .profileType(ProfileType.USER_LOCKED)
            .usesLeft(0)
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByToken("tok-abc")).thenReturn(Optional.of(token));

    assertThatThrownBy(() -> service.downloadFromToken("tok-abc"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "token_exhausted");
  }

  @Test
  void tokenWithSingleUseLeftIsConsumedToZero() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok-abc")
            .userId("u1")
            .profileType(ProfileType.USER_LOCKED)
            .usesLeft(1)
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByToken("tok-abc")).thenReturn(Optional.of(token));
    when(tokenRepository.save(token)).thenReturn(token);

    service.downloadFromToken("tok-abc");

    assertThat(token.getUsesLeft()).isEqualTo(0);
  }

  @Test
  void expiredTokenIsRejected() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok-abc")
            .userId("u1")
            .profileType(ProfileType.USER_LOCKED)
            .expiresAt(Instant.now().minusSeconds(1))
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByToken("tok-abc")).thenReturn(Optional.of(token));

    assertThatThrownBy(() -> service.downloadFromToken("tok-abc"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "token_expired");
  }

  @Test
  void tokenNotYetPastExpiryIsUsable() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok-abc")
            .userId("u1")
            .profileType(ProfileType.USER_LOCKED)
            .expiresAt(Instant.now().plusSeconds(30))
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByToken("tok-abc")).thenReturn(Optional.of(token));

    OvpnFile file = service.downloadFromToken("tok-abc");

    assertThat(file.filename()).isEqualTo("user-locked-alice.ovpn");
  }

  @Test
  void genericTokenRequiresANonAdminUser() {
    when(userRepository.findAll())
        .thenReturn(
            java.util.List.of(
                User.builder().id("a1").username("root").role(User.Role.ADMIN).build()));

    assertThatThrownBy(() -> service.createToken(null, ProfileType.GENERIC, null, 3))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "no_user");
  }

  @Test
  void genericTokenDownloadWithNoNonAdminUserIsRejected() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok-abc")
            .userId(null)
            .profileType(ProfileType.GENERIC)
            .usesLeft(3)
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByToken("tok-abc")).thenReturn(Optional.of(token));
    when(userRepository.findAll()).thenReturn(java.util.List.of());

    assertThatThrownBy(() -> service.downloadFromToken("tok-abc"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "no_user");
  }
}
