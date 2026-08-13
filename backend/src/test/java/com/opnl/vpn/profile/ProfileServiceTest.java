package com.opnl.vpn.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.pki.CertService;
import com.opnl.vpn.pki.EasyRsaService;
import com.opnl.vpn.profile.ProfileService.OvpnFile;
import com.opnl.vpn.profile.ProfileService.QrPayload;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileServiceTest {

  private CertService certService;
  private EasyRsaService easyRsa;
  private DaemonService daemonService;
  private UserRepository userRepository;
  private ProfileTokenRepository tokenRepository;
  private SettingsService settingsService;
  private OpnlProperties properties;
  private OpnlProperties.OpenVpn openvpn;
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
    daemonService = mock(DaemonService.class);
    userRepository = mock(UserRepository.class);
    tokenRepository = mock(ProfileTokenRepository.class);
    settingsService = mock(SettingsService.class);
    properties = mock(OpnlProperties.class);
    openvpn = mock(OpnlProperties.OpenVpn.class);
    when(properties.openvpn()).thenReturn(openvpn);
    when(settingsService.serverSettings()).thenReturn(java.util.Map.of());
    service =
        new ProfileService(
            new OvpnGenerator(),
            certService,
            easyRsa,
            daemonService,
            userRepository,
            tokenRepository,
            settingsService,
            properties);

    ServerConfig config = ServerConfig.defaults();
    when(daemonService.resolveForProfile(org.mockito.ArgumentMatchers.any())).thenReturn(config);
    when(easyRsa.caCert()).thenReturn("CA-CERT");
    when(easyRsa.taKey()).thenReturn("TA-KEY");
    when(easyRsa.clientCert("alice")).thenReturn("CERT");
    when(easyRsa.clientKey("alice")).thenReturn("KEY");
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice()));
  }

  @Test
  void profileUsesDaemonResolvedForItsType() {
    ServerConfig certOnly =
        new ServerConfig(
            1,
            1195,
            ServerConfig.Protocol.udp,
            "10.9.0.0",
            "255.255.255.0",
            List.of("1.1.1.1"),
            null,
            List.of(),
            true,
            false,
            false,
            "vpn.example.com",
            false,
            null);
    when(daemonService.resolveForProfile(ProfileType.AUTO_LOGIN)).thenReturn(certOnly);

    OvpnFile file = service.downloadForUser("u1", ProfileType.AUTO_LOGIN);

    assertThat(file.content()).contains("remote vpn.example.com 1195");
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
  void userLockedProfileAddsStaticChallengeWhenUserHasMfa() {
    User mfaUser =
        User.builder()
            .id("u1")
            .username("alice")
            .role(User.Role.USER)
            .mfaEnabled(true)
            .mfaSecret("SECRET")
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(mfaUser));

    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.content())
        .contains("auth-user-pass")
        .contains("static-challenge \"Verification code\" 1");
  }

  @Test
  void userLockedProfileOmitsStaticChallengeWhenNoMfaInForce() {
    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.content()).contains("auth-user-pass").doesNotContain("static-challenge");
  }

  @Test
  void genericProfileAddsStaticChallengeWhenServerRequiresMfaOnConnect() {
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.REQUIRE_MFA_ON_CONNECT, true));

    OvpnFile file = service.downloadForUser("u1", ProfileType.GENERIC);

    assertThat(file.content())
        .contains("auth-user-pass")
        .contains("static-challenge \"Verification code\" 1");
  }

  @Test
  void serverLockedProfileAddsStaticChallengeWhenServerRequiresMfaOnConnect() {
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.REQUIRE_MFA_ON_CONNECT, true));

    OvpnFile file = service.downloadForUser("u1", ProfileType.SERVER_LOCKED);

    assertThat(file.content())
        .contains("auth-user-pass")
        .contains("static-challenge \"Verification code\" 1");
  }

  @Test
  void autoLoginProfileNeverAddsStaticChallengeEvenWithServerPolicy() {
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.REQUIRE_MFA_ON_CONNECT, true));

    OvpnFile file = service.downloadForUser("u1", ProfileType.AUTO_LOGIN);

    assertThat(file.content()).doesNotContain("auth-user-pass").doesNotContain("static-challenge");
  }

  @Test
  void configuredAdminHostOverridesServerConfigHost() {
    when(openvpn.adminHost()).thenReturn("65.21.108.250");

    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.content()).contains("remote 65.21.108.250 1194");
  }

  @Test
  void qrPayloadCreatesSingleUseToken() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("qr-tok")
            .userId("u1")
            .profileType(ProfileType.USER_LOCKED)
            .usesLeft(1)
            .expiresAt(Instant.now().plusSeconds(3600))
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.save(org.mockito.ArgumentMatchers.any(ProfileToken.class)))
        .thenReturn(token);

    QrPayload payload = service.createQrPayload("u1", ProfileType.USER_LOCKED);

    assertThat(payload.token()).isEqualTo("qr-tok");
    assertThat(payload.expiresAt()).isNotNull();
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
