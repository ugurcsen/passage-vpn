package com.opnl.vpn.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.pki.Certificate.Status;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CertServiceTest {

  private EasyRsaService easyRsa;
  private CertificateRepository certificateRepository;
  private UserRepository userRepository;
  private SettingsService settingsService;
  private CertService service;

  private User user() {
    return User.builder().id("u1").username("alice").createdAt(Instant.now()).build();
  }

  @BeforeEach
  void setUp() {
    easyRsa = mock(EasyRsaService.class);
    certificateRepository = mock(CertificateRepository.class);
    userRepository = mock(UserRepository.class);
    settingsService = mock(SettingsService.class);
    when(settingsService.serverSettings()).thenReturn(Map.of());
    service =
        new CertService(
            easyRsa,
            certificateRepository,
            userRepository,
            mock(AuditLogService.class),
            settingsService);
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
    when(easyRsa.hasClientCert("alice")).thenReturn(true);

    Certificate cert = service.ensureUserCert("u1");

    assertThat(cert).isSameAs(existing);
    verify(easyRsa, never()).issueClientCert("alice");
  }

  @Test
  void ensureUserCertIssuesArtifactWhenValidRowHasNoFile() {
    Certificate existing =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .status(Status.VALID)
            .serial("11")
            .build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
    when(certificateRepository.findByUserIdAndStatus("u1", Status.VALID))
        .thenReturn(List.of(existing));
    when(easyRsa.hasClientCert("alice")).thenReturn(false);
    when(easyRsa.index())
        .thenReturn(
            List.of(
                new CertIndexEntry(
                    CertIndexEntry.Status.VALID,
                    Instant.now().plusSeconds(86400),
                    "AB",
                    "alice.crt",
                    "alice")));
    when(certificateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Certificate cert = service.ensureUserCert("u1");

    assertThat(cert).isSameAs(existing);
    assertThat(cert.getSerial()).isEqualTo("AB");
    assertThat(cert.getIssuedAt()).isNotNull();
    verify(easyRsa).issueClientCert("alice");
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
  void ensureUserCertPurgesStaleCertFromDeletedAccountWithSameName() {
    Certificate stale =
        Certificate.builder()
            .id("old-c1")
            .commonName("alice")
            .userId("deleted-user")
            .status(Status.VALID)
            .serial("FF")
            .build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
    when(certificateRepository.findByUserIdAndStatus("u1", Status.VALID)).thenReturn(List.of());
    when(certificateRepository.findByCommonName("alice")).thenReturn(Optional.of(stale));
    when(easyRsa.hasClientCert("alice")).thenReturn(true);
    when(easyRsa.index()).thenReturn(List.of());
    when(certificateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Certificate cert = service.ensureUserCert("u1");

    assertThat(cert.getUserId()).isEqualTo("u1");
    verify(easyRsa).revokeCert("alice");
    verify(easyRsa).deleteClientCert("alice");
    // Bulk delete runs immediately so the new row's INSERT cannot collide with the stale row.
    verify(certificateRepository).deleteByCommonNameAndUserIdNot("alice", "u1");
    verify(certificateRepository, never()).delete(any());
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
    verify(easyRsa).unrevokeCert("AB", "alice");
  }

  @Test
  void restoreFallsBackToCommonNameWhenSerialMissing() {
    Certificate cert =
        Certificate.builder()
            .id("c1")
            .commonName("bob")
            .userId("u1")
            .status(Status.REVOKED)
            .revokedAt(Instant.now())
            .build();
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(cert));
    when(certificateRepository.save(cert)).thenReturn(cert);

    Certificate restored = service.restore("c1");

    assertThat(restored.getStatus()).isEqualTo(Status.VALID);
    assertThat(restored.getRevokedAt()).isNull();
    verify(easyRsa).unrevokeCert(null, "bob");
  }

  @Test
  void restoreThrowsWhenCertificateNotRevoked() {
    Certificate cert =
        Certificate.builder().id("c1").commonName("alice").status(Status.VALID).build();
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(cert));

    assertThatThrownBy(() -> service.restore("c1"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "not_revoked");
    verify(easyRsa, never()).unrevokeCert(any(), any());
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
  void restoreThenRotateSucceeds() {
    Certificate revoked =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .serial("AB")
            .status(Status.REVOKED)
            .revokedAt(Instant.now())
            .build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(revoked));
    when(certificateRepository.findByUserIdAndStatus("u1", Status.VALID))
        .thenReturn(List.of(revoked));
    when(certificateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    // After restore the artifact is back on disk, so rotate's revoke finds it.
    when(easyRsa.hasClientCert("alice")).thenReturn(true);
    when(easyRsa.index())
        .thenReturn(
            List.of(
                new CertIndexEntry(
                    CertIndexEntry.Status.VALID,
                    Instant.now().plusSeconds(86400),
                    "AB2",
                    "alice.crt",
                    "alice")));

    Certificate restored = service.restore("c1");
    Certificate rotated = service.rotate("u1");

    assertThat(restored.getStatus()).isEqualTo(Status.VALID);
    assertThat(rotated.getStatus()).isEqualTo(Status.VALID);
    assertThat(rotated.getSerial()).isEqualTo("AB2");
    verify(easyRsa).unrevokeCert("AB", "alice");
    verify(easyRsa).revokeCert("alice");
    verify(easyRsa).issueClientCert("alice");
  }

  @Test
  void restoreThenRevokeSucceeds() {
    Certificate revoked =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .serial("AB")
            .status(Status.REVOKED)
            .revokedAt(Instant.now())
            .build();
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(revoked));
    when(certificateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(easyRsa.hasClientCert("alice")).thenReturn(true);

    Certificate restored = service.restore("c1");
    assertThat(restored.getStatus()).isEqualTo(Status.VALID);

    Certificate revokedAgain = service.revoke("c1");

    assertThat(revokedAgain.getStatus()).isEqualTo(Status.REVOKED);
    verify(easyRsa, times(1)).revokeCert("alice");
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

  // ---- auto-rotation policy -------------------------------------------------

  @Test
  void rotationPolicyDefaultsToNotify() {
    assertThat(service.rotationPolicy()).isEqualTo("notify");
  }

  @Test
  void rotationPolicyReadsServerSetting() {
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.CERT_AUTO_ROTATE, "auto"));
    assertThat(service.rotationPolicy()).isEqualTo("auto");
  }

  @Test
  void rotationPolicyRejectsUnknownValues() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of(SettingKeys.CERT_AUTO_ROTATE, "sometimes"));
    assertThat(service.rotationPolicy()).isEqualTo("notify");
  }

  @Test
  void rotationDaysBeforeDefaultsTo14() {
    assertThat(service.rotationDaysBefore()).isEqualTo(14);
  }

  @Test
  void rotationDaysBeforeIsClamped() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of(SettingKeys.CERT_ROTATE_DAYS_BEFORE, -5));
    assertThat(service.rotationDaysBefore()).isEqualTo(1);
    when(settingsService.serverSettings())
        .thenReturn(Map.of(SettingKeys.CERT_ROTATE_DAYS_BEFORE, 99999));
    assertThat(service.rotationDaysBefore()).isEqualTo(3650);
  }

  @Test
  void applyRotationPolicyDoesNothingWhenNotAuto() {
    service.applyRotationPolicy();
    verify(certificateRepository, never()).findByStatusAndExpiresAtBetween(any(), any(), any());
  }

  @Test
  void applyRotationPolicyRotatesExpiringCertsWhenAuto() {
    Certificate expiring =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .serial("OLD")
            .status(Status.VALID)
            .build();
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.CERT_AUTO_ROTATE, "auto"));
    when(userRepository.existsById("u1")).thenReturn(true);
    when(certificateRepository.findByStatusAndExpiresAtBetween(eq(Status.VALID), any(), any()))
        .thenReturn(List.of(expiring));
    when(userRepository.findById("u1")).thenReturn(Optional.of(user()));
    when(certificateRepository.findByUserIdAndStatus("u1", Status.VALID))
        .thenReturn(List.of(expiring));
    when(certificateRepository.findById("c1")).thenReturn(Optional.of(expiring));
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

    service.applyRotationPolicy();

    verify(easyRsa).revokeCert("alice");
    verify(easyRsa).issueClientCert("alice");
    assertThat(expiring.getSerial()).isEqualTo("NEW");
  }

  @Test
  void applyRotationPolicySkipsOrphanedCertificates() {
    Certificate orphan =
        Certificate.builder()
            .id("c1")
            .commonName("ghost")
            .userId(null)
            .serial("S1")
            .status(Status.VALID)
            .build();
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.CERT_AUTO_ROTATE, "auto"));
    when(certificateRepository.findByStatusAndExpiresAtBetween(eq(Status.VALID), any(), any()))
        .thenReturn(List.of(orphan));

    service.applyRotationPolicy();

    verify(easyRsa, never()).revokeCert(any());
    verify(easyRsa, never()).issueClientCert(any());
  }

  // ---- reconcile ----------------------------------------------------------

  private CertIndexEntry entry(
      CertIndexEntry.Status status, String cn, String serial, Instant revokedAt) {
    return new CertIndexEntry(
        status, Instant.now().plusSeconds(86400), revokedAt, serial, cn + ".crt", cn);
  }

  @Test
  void reconcileCreatesRowsForMissingCertificatesAndLinksUsers() {
    when(easyRsa.index())
        .thenReturn(
            List.of(
                entry(CertIndexEntry.Status.VALID, "alice", "01", null),
                entry(CertIndexEntry.Status.REVOKED, "bob", "02", Instant.now().minusSeconds(60))));
    when(certificateRepository.findAll()).thenReturn(List.of());
    when(easyRsa.hasClientCert("alice")).thenReturn(true);
    when(easyRsa.hasClientCert("bob")).thenReturn(false);
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user()));

    CertService.ReconcileResult result = service.reconcile();

    assertThat(result).isEqualTo(new CertService.ReconcileResult(2, 0, 0));
    org.mockito.ArgumentCaptor<List<Certificate>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(certificateRepository).saveAll(captor.capture());
    List<Certificate> rows = captor.getValue();
    Certificate alice =
        rows.stream().filter(r -> r.getCommonName().equals("alice")).findFirst().orElseThrow();
    assertThat(alice.getStatus()).isEqualTo(Status.VALID);
    assertThat(alice.getSerial()).isEqualTo("01");
    assertThat(alice.getUserId()).isEqualTo("u1");
    Certificate bob =
        rows.stream().filter(r -> r.getCommonName().equals("bob")).findFirst().orElseThrow();
    assertThat(bob.getStatus()).isEqualTo(Status.REVOKED);
    assertThat(bob.getRevokedAt()).isNotNull();
  }

  @Test
  void reconcileUpdatesExistingRowFromIndex() {
    Certificate existing =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .status(Status.REVOKED)
            .serial("OLD")
            .revokedAt(Instant.now().minusSeconds(120))
            .build();
    when(easyRsa.index())
        .thenReturn(List.of(entry(CertIndexEntry.Status.VALID, "alice", "NEW", null)));
    when(certificateRepository.findAll()).thenReturn(List.of(existing));

    CertService.ReconcileResult result = service.reconcile();

    assertThat(result).isEqualTo(new CertService.ReconcileResult(0, 1, 0));
    assertThat(existing.getStatus()).isEqualTo(Status.VALID);
    assertThat(existing.getSerial()).isEqualTo("NEW");
    assertThat(existing.getRevokedAt()).isNull();
  }

  @Test
  void reconcileMatchesExistingRowBySerialForRevokedEntry() {
    Certificate existing =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .status(Status.VALID)
            .serial("AB")
            .build();
    when(easyRsa.index())
        .thenReturn(
            List.of(
                entry(
                    CertIndexEntry.Status.REVOKED, "alice", "AB", Instant.now().minusSeconds(60))));
    when(certificateRepository.findAll()).thenReturn(List.of(existing));

    CertService.ReconcileResult result = service.reconcile();

    assertThat(result).isEqualTo(new CertService.ReconcileResult(0, 1, 0));
    assertThat(existing.getStatus()).isEqualTo(Status.REVOKED);
    assertThat(existing.getRevokedAt()).isNotNull();
  }

  @Test
  void reconcileSkipsServerAndPhantomEntries() {
    when(easyRsa.index())
        .thenReturn(
            List.of(
                entry(CertIndexEntry.Status.VALID, "server", "S1", null),
                entry(CertIndexEntry.Status.VALID, "s7certuser", "S2", null),
                entry(CertIndexEntry.Status.VALID, "bob", "S3", null)));
    when(certificateRepository.findAll()).thenReturn(List.of());
    when(easyRsa.hasClientCert("bob")).thenReturn(true);
    when(easyRsa.hasClientCert("s7certuser")).thenReturn(false);

    CertService.ReconcileResult result = service.reconcile();

    assertThat(result).isEqualTo(new CertService.ReconcileResult(1, 0, 1));
    org.mockito.ArgumentCaptor<List<Certificate>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(certificateRepository).saveAll(captor.capture());
    assertThat(captor.getValue()).extracting(Certificate::getCommonName).containsExactly("bob");
  }

  @Test
  void reconcilePrefersValidEntryWhenCommonNameRepeats() {
    when(easyRsa.index())
        .thenReturn(
            List.of(
                entry(
                    CertIndexEntry.Status.REVOKED, "alice", "OLD", Instant.now().minusSeconds(60)),
                entry(CertIndexEntry.Status.VALID, "alice", "NEW", null)));
    when(certificateRepository.findAll()).thenReturn(List.of());
    when(easyRsa.hasClientCert("alice")).thenReturn(true);

    CertService.ReconcileResult result = service.reconcile();

    assertThat(result).isEqualTo(new CertService.ReconcileResult(1, 0, 0));
    org.mockito.ArgumentCaptor<List<Certificate>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(certificateRepository).saveAll(captor.capture());
    Certificate row = captor.getValue().get(0);
    assertThat(row.getSerial()).isEqualTo("NEW");
    assertThat(row.getStatus()).isEqualTo(Status.VALID);
  }

  @Test
  void reconcileIsIdempotentWhenNothingChanged() {
    Instant expiry = Instant.now().plusSeconds(86400);
    Certificate existing =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .status(Status.VALID)
            .serial("01")
            .expiresAt(expiry)
            .build();
    when(easyRsa.index())
        .thenReturn(
            List.of(
                new CertIndexEntry(
                    CertIndexEntry.Status.VALID, expiry, null, "01", "alice.crt", "alice")));
    when(certificateRepository.findAll()).thenReturn(List.of(existing));

    CertService.ReconcileResult result = service.reconcile();

    assertThat(result).isEqualTo(new CertService.ReconcileResult(0, 0, 0));
    verify(certificateRepository).saveAll(List.of());
  }

  @Test
  void purgeForUserRevokesAndDeletesCertificates() {
    Certificate valid =
        Certificate.builder()
            .id("c1")
            .commonName("alice")
            .userId("u1")
            .status(Status.VALID)
            .serial("01")
            .build();
    Certificate revoked =
        Certificate.builder()
            .id("c2")
            .commonName("bob")
            .userId("u1")
            .status(Status.REVOKED)
            .serial("02")
            .build();
    when(certificateRepository.findByUserId("u1")).thenReturn(List.of(valid, revoked));
    when(easyRsa.hasClientCert("alice")).thenReturn(true);

    service.purgeForUser("u1");

    verify(easyRsa).revokeCert("alice");
    verify(easyRsa, never()).revokeCert("bob");
    verify(easyRsa).deleteClientCert("alice");
    verify(easyRsa).deleteClientCert("bob");
    verify(certificateRepository).delete(valid);
    verify(certificateRepository).delete(revoked);
  }

  @Test
  void purgeForUserIsNoopWhenNoCertificates() {
    when(certificateRepository.findByUserId("u1")).thenReturn(List.of());

    service.purgeForUser("u1");

    verify(certificateRepository, never()).delete(any());
  }
}
