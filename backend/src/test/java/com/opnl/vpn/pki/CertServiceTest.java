package com.opnl.vpn.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.pki.Certificate.Status;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CertServiceTest {

  private EasyRsaService easyRsa;
  private CertificateRepository certificateRepository;
  private UserRepository userRepository;
  private CertService service;

  private User user() {
    return User.builder().id("u1").username("alice").createdAt(Instant.now()).build();
  }

  @BeforeEach
  void setUp() {
    easyRsa = mock(EasyRsaService.class);
    certificateRepository = mock(CertificateRepository.class);
    userRepository = mock(UserRepository.class);
    service = new CertService(easyRsa, certificateRepository, userRepository);
  }

  @Test
  void ensureUserCertIssuesWhenNoValidCertificateExists() {
    when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
    when(certificateRepository.findByUserIdAndStatus("u1", Status.VALID)).thenReturn(List.of());
    when(easyRsa.hasClientCert("alice")).thenReturn(false);
    when(easyRsa.index())
        .thenReturn(
            List.of(
                new CertIndexEntry(
                    CertIndexEntry.Status.VALID,
                    Instant.now().plusSeconds(86400),
                    "01",
                    "alice.crt",
                    "alice")));
    when(certificateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Certificate cert = service.ensureUserCert("u1");

    assertThat(cert.getStatus()).isEqualTo(Status.VALID);
    assertThat(cert.getCommonName()).isEqualTo("alice");
    assertThat(cert.getSerial()).isEqualTo("01");
    verify(easyRsa).issueClientCert("alice");
  }

  @Test
  void ensureUserCertReusesExistingValidCertificate() {
    Certificate existing =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .status(Status.VALID)
            .build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
    when(certificateRepository.findByUserIdAndStatus("u1", Status.VALID))
        .thenReturn(List.of(existing));

    Certificate cert = service.ensureUserCert("u1");

    assertThat(cert).isSameAs(existing);
    verify(easyRsa, never()).issueClientCert("alice");
  }

  @Test
  void revokeMarksRevokedAndRegeneratesCrl() {
    Certificate cert =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .status(Status.VALID)
            .build();
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(cert));
    when(certificateRepository.save(cert)).thenReturn(cert);

    Certificate revoked = service.revoke("c1");

    assertThat(revoked.getStatus()).isEqualTo(Status.REVOKED);
    assertThat(revoked.getRevokedAt()).isNotNull();
    verify(easyRsa).revokeCert("alice");
  }

  @Test
  void revokeThrowsWhenAlreadyRevoked() {
    Certificate cert =
        Certificate.builder().id("c1").commonName("alice").status(Status.REVOKED).build();
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(cert));

    assertThatThrownBy(() -> service.revoke("c1"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "already_revoked");
  }

  @Test
  void ensureUserCertThrowsWhenUserMissing() {
    when(userRepository.findById("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.ensureUserCert("missing")).isInstanceOf(ApiException.class);
  }

  @Test
  void restoreReactivatesRevokedCertificate() {
    Certificate cert =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .serial("AB")
            .status(Status.REVOKED)
            .revokedAt(Instant.now())
            .build();
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(cert));
    when(certificateRepository.save(cert)).thenReturn(cert);

    Certificate restored = service.restore("c1");

    assertThat(restored.getStatus()).isEqualTo(Status.VALID);
    assertThat(restored.getRevokedAt()).isNull();
    verify(easyRsa).unrevokeCert("AB");
  }

  @Test
  void restoreThrowsWhenCertificateNotRevoked() {
    Certificate cert =
        Certificate.builder().id("c1").commonName("alice").status(Status.VALID).build();
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(cert));

    assertThatThrownBy(() -> service.restore("c1"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "not_revoked");
    verify(easyRsa, never()).unrevokeCert(any());
  }

  @Test
  void restoreThrowsWhenCertificateMissing() {
    when(certificateRepository.findById("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.restore("missing"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "certificate_not_found");
  }

  @Test
  void rotateRevokesOldAndIssuesNewCertificate() {
    Certificate valid =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .serial("OLD")
            .status(Status.VALID)
            .build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
    when(certificateRepository.findByUserIdAndStatus("u1", Status.VALID))
        .thenReturn(List.of(valid));
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(valid));
    when(certificateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(easyRsa.index())
        .thenReturn(
            List.of(
                new CertIndexEntry(
                    CertIndexEntry.Status.VALID,
                    Instant.now().plusSeconds(86400),
                    "NEW",
                    "alice.crt",
                    "alice")));

    Certificate rotated = service.rotate("u1");

    assertThat(rotated.getStatus()).isEqualTo(Status.VALID);
    assertThat(rotated.getSerial()).isEqualTo("NEW");
    assertThat(rotated.getIssuedAt()).isNotNull();
    verify(easyRsa).revokeCert("alice");
    verify(easyRsa).deleteClientCert("alice");
    verify(easyRsa).issueClientCert("alice");
  }

  @Test
  void rotateThrowsWhenNoValidCertificate() {
    when(certificateRepository.findByUserIdAndStatus("u1", Status.VALID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.rotate("u1"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "no_valid_certificate");
    verify(easyRsa, never()).revokeCert(any());
  }

  @Test
  void markExpiredCertificatesFlagsPastExpiry() {
    Certificate expired =
        Certificate.builder().id("c1").commonName("alice").status(Status.VALID).build();
    when(certificateRepository.findByStatusAndExpiresAtBefore(eq(Status.VALID), any()))
        .thenReturn(List.of(expired));

    service.markExpiredCertificates();

    assertThat(expired.getStatus()).isEqualTo(Status.EXPIRED);
    verify(certificateRepository).saveAll(List.of(expired));
  }

  @Test
  void expiringSoonReturnsCertificatesWithinWindow() {
    Certificate soon =
        Certificate.builder().id("c1").commonName("alice").status(Status.VALID).build();
    when(certificateRepository.findByStatusAndExpiresAtBetween(eq(Status.VALID), any(), any()))
        .thenReturn(List.of(soon));

    assertThat(service.expiringSoon()).containsExactly(soon);
  }
}
