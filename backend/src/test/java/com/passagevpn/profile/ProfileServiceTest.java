package com.passagevpn.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.common.ApiException;
import com.passagevpn.network.DaemonService;
import com.passagevpn.network.ServerConfig;
import com.passagevpn.pki.CertService;
import com.passagevpn.pki.EasyRsaService;
import com.passagevpn.profile.ProfileService.OvpnFile;
import com.passagevpn.profile.ProfileService.QrPayload;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProfileServiceTest {

  private CertService certService;
  private EasyRsaService easyRsa;
  private DaemonService daemonService;
  private UserRepository userRepository;
  private ProfileTokenRepository tokenRepository;
  private SettingsService settingsService;
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
    when(settingsService.serverSettings()).thenReturn(java.util.Map.of());
    service =
        new ProfileService(
            new OvpnGenerator(),
            certService,
            easyRsa,
            daemonService,
            userRepository,
            tokenRepository,
            settingsService);

    ServerConfig config = ServerConfig.defaults();
    when(daemonService.resolveAllForProfile(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(new DaemonService.ProfileEndpoint(config, "vpn.example.com")));
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
    when(daemonService.resolveAllForProfile(ProfileType.AUTO_LOGIN))
        .thenReturn(List.of(new DaemonService.ProfileEndpoint(certOnly, "vpn.example.com")));

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
  void passwordAuthProfileOmitsStaticChallenge() {
    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.content()).contains("auth-user-pass").doesNotContain("static-challenge");
  }

  @Test
  void genericProfileOmitsStaticChallengeEvenWithServerPolicy() {
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.REQUIRE_MFA_ON_CONNECT, true));

    OvpnFile file = service.downloadForUser("u1", ProfileType.GENERIC);

    assertThat(file.content()).contains("auth-user-pass").doesNotContain("static-challenge");
  }

  @Test
  void serverLockedProfileOmitsStaticChallengeEvenWithServerPolicy() {
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.REQUIRE_MFA_ON_CONNECT, true));

    OvpnFile file = service.downloadForUser("u1", ProfileType.SERVER_LOCKED);

    assertThat(file.content()).contains("auth-user-pass").doesNotContain("static-challenge");
  }

  @Test
  void autoLoginProfileNeverAddsStaticChallengeEvenWithServerPolicy() {
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.REQUIRE_MFA_ON_CONNECT, true));

    OvpnFile file = service.downloadForUser("u1", ProfileType.AUTO_LOGIN);

    assertThat(file.content()).doesNotContain("auth-user-pass").doesNotContain("static-challenge");
  }

  @Test
  void profileUsesResolvedEndpointHost() {
    when(daemonService.resolveAllForProfile(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(new DaemonService.ProfileEndpoint(ServerConfig.defaults(), "65.21.108.250")));

    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.content()).contains("remote 65.21.108.250 1194");
  }

  @Test
  void profileEmbedsAllEndpointsWhenMultiRemoteEnabled() {
    ServerConfig second =
        new ServerConfig(
            1,
            1195,
            ServerConfig.Protocol.tcp,
            "10.9.0.0",
            "255.255.255.0",
            List.of("1.1.1.1"),
            null,
            List.of(),
            true,
            false,
            true,
            "vpn-us.example.com",
            false,
            null);
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.PROFILE_MULTI_REMOTE, true));
    when(daemonService.resolveAllForProfile(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new DaemonService.ProfileEndpoint(ServerConfig.defaults(), "vpn-eu.example.com"),
                new DaemonService.ProfileEndpoint(second, "vpn-us.example.com")));

    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.content())
        .contains("remote vpn-eu.example.com 1194 udp")
        .contains("remote vpn-us.example.com 1195 tcp")
        .contains("remote-random");
  }

  @Test
  void profileDefaultsToMultiRemoteWhenSettingUnset() {
    ServerConfig second =
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
            true,
            "vpn-us.example.com",
            false,
            null);
    when(daemonService.resolveAllForProfile(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new DaemonService.ProfileEndpoint(ServerConfig.defaults(), "vpn-eu.example.com"),
                new DaemonService.ProfileEndpoint(second, "vpn-us.example.com")));

    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.content()).contains("remote vpn-us.example.com 1195 udp");
  }

  @Test
  void profilePinsFirstEndpointWhenMultiRemoteDisabled() {
    ServerConfig second =
        new ServerConfig(
            1,
            1195,
            ServerConfig.Protocol.tcp,
            "10.9.0.0",
            "255.255.255.0",
            List.of("1.1.1.1"),
            null,
            List.of(),
            true,
            false,
            true,
            "vpn-us.example.com",
            false,
            null);
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.PROFILE_MULTI_REMOTE, false));
    when(daemonService.resolveAllForProfile(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new DaemonService.ProfileEndpoint(ServerConfig.defaults(), "vpn-eu.example.com"),
                new DaemonService.ProfileEndpoint(second, "vpn-us.example.com")));

    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED);

    assertThat(file.content())
        .contains("remote vpn-eu.example.com 1194")
        .doesNotContain("remote vpn-us.example.com")
        .doesNotContain("remote-random");
  }

  @Test
  void pinnedDaemonProfileEmbedsOnlyThatDaemonEvenWhenMultiRemoteEnabled() {
    ServerConfig split =
        new ServerConfig(
            1,
            1195,
            ServerConfig.Protocol.udp,
            "10.9.0.0",
            "255.255.255.0",
            List.of("1.1.1.1"),
            null,
            List.of("192.168.50.0/24"),
            false,
            false,
            true,
            "vpn.example.com",
            false,
            null);
    when(settingsService.serverSettings())
        .thenReturn(java.util.Map.of(SettingKeys.PROFILE_MULTI_REMOTE, true));
    when(daemonService.resolvePinnedForProfile(ProfileType.USER_LOCKED, 1))
        .thenReturn(new DaemonService.ProfileEndpoint(split, "vpn.example.com", "Split tunnel"));

    OvpnFile file = service.downloadForUser("u1", ProfileType.USER_LOCKED, 1);

    assertThat(file.filename()).isEqualTo("user-locked-Split_tunnel-alice.ovpn");
    assertThat(file.content())
        .contains("remote vpn.example.com 1195")
        .doesNotContain("remote-random");
  }

  @Test
  void tokenDownloadUsesPinnedDaemon() {
    ServerConfig split =
        new ServerConfig(
            1,
            1195,
            ServerConfig.Protocol.udp,
            "10.9.0.0",
            "255.255.255.0",
            List.of("1.1.1.1"),
            null,
            List.of("192.168.50.0/24"),
            false,
            false,
            true,
            "vpn.example.com",
            false,
            null);
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok-abc")
            .userId("u1")
            .profileType(ProfileType.USER_LOCKED)
            .daemonIndex(1)
            .usesLeft(3)
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.findByToken("tok-abc")).thenReturn(Optional.of(token));
    when(tokenRepository.save(token)).thenReturn(token);
    when(daemonService.resolvePinnedForProfile(ProfileType.USER_LOCKED, 1))
        .thenReturn(new DaemonService.ProfileEndpoint(split, "vpn.example.com", "Split tunnel"));

    OvpnFile file = service.downloadFromToken("tok-abc");

    assertThat(file.content())
        .contains("remote vpn.example.com 1195")
        .doesNotContain("remote-random");
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
    ArgumentCaptor<ProfileToken> saved = ArgumentCaptor.forClass(ProfileToken.class);
    verify(tokenRepository).save(saved.capture());
    assertThat(saved.getValue().getUsesLeft()).isEqualTo(1);
    assertThat(saved.getValue().getSource()).isEqualTo(TokenSource.PORTAL);
    assertThat(saved.getValue().getExpiresAt())
        .isBetween(
            Instant.now().minusSeconds(2),
            Instant.now().plus(ProfileService.QR_TOKEN_TTL).plusSeconds(2));
  }

  @Test
  void adminCreateTokenDefaultsToAdminSource() {
    ProfileToken token =
        ProfileToken.builder()
            .id("t1")
            .token("tok")
            .userId("u1")
            .profileType(ProfileType.USER_LOCKED)
            .createdAt(Instant.now())
            .build();
    when(tokenRepository.save(org.mockito.ArgumentMatchers.any(ProfileToken.class)))
        .thenReturn(token);

    service.createToken("u1", ProfileType.USER_LOCKED, null, 3);

    ArgumentCaptor<ProfileToken> saved = ArgumentCaptor.forClass(ProfileToken.class);
    verify(tokenRepository).save(saved.capture());
    assertThat(saved.getValue().getSource()).isEqualTo(TokenSource.ADMIN);
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
