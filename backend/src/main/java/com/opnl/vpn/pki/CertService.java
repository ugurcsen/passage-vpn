package com.opnl.vpn.pki;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user certificate lifecycle: issues client certificates via Easy-RSA, keeps bookkeeping in
 * sync, revokes, restores, rotates and downloads. The Easy-RSA index.txt remains the source of
 * truth for status. A daily scheduled scan flags expired certificates for the admin UI.
 */
@Slf4j
@Service
public class CertService {

  /** Certificates expiring within this window are reported by {@link #expiringSoon()}. */
  public static final int EXPIRY_WARNING_DAYS = 30;

  private final EasyRsaService easyRsaService;
  private final CertificateRepository certificateRepository;
  private final UserRepository userRepository;
  private final AuditLogService auditLogService;

  public CertService(
      EasyRsaService easyRsaService,
      CertificateRepository certificateRepository,
      UserRepository userRepository,
      AuditLogService auditLogService) {
    this.easyRsaService = easyRsaService;
    this.certificateRepository = certificateRepository;
    this.userRepository = userRepository;
    this.auditLogService = auditLogService;
  }

  /** Returns the user's current valid certificate, issuing one when missing. */
  @Transactional
  public Certificate ensureUserCert(String userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
    List<Certificate> valid =
        certificateRepository.findByUserIdAndStatus(userId, Certificate.Status.VALID);
    if (!valid.isEmpty()) {
      return valid.get(0);
    }
    String cn = user.getUsername();
    if (!easyRsaService.hasClientCert(cn)) {
      easyRsaService.issueClientCert(cn);
    }
    Certificate certificate =
        Certificate.builder()
            .id(UUID.randomUUID().toString())
            .commonName(cn)
            .userId(userId)
            .status(Certificate.Status.VALID)
            .serial(serialFor(cn))
            .issuedAt(Instant.now())
            .expiresAt(expiryFor(cn))
            .build();
    auditLogService.record(
        "CERT_ISSUE",
        AuditLogService.CAT_CERT,
        certificate.getId(),
        "certificate",
        Map.of("commonName", cn));
    return certificateRepository.save(certificate);
  }

  /** Revokes the user's valid certificate and generates a fresh CRL. */
  @Transactional
  public Certificate revoke(String certificateId) {
    Certificate certificate =
        certificateRepository
            .findById(certificateId)
            .orElseThrow(
                () -> ApiException.notFound("certificate_not_found", "Certificate not found"));
    if (certificate.getStatus() == Certificate.Status.REVOKED) {
      throw ApiException.conflict("already_revoked", "Certificate is already revoked");
    }
    easyRsaService.revokeCert(certificate.getCommonName());
    certificate.setStatus(Certificate.Status.REVOKED);
    certificate.setRevokedAt(Instant.now());
    auditLogService.record(
        "CERT_REVOKE",
        AuditLogService.CAT_CERT,
        certificate.getId(),
        "certificate",
        Map.of("commonName", certificate.getCommonName()));
    return certificateRepository.save(certificate);
  }

  /** Revokes any valid certificate held by a user (used when deleting an account). */
  @Transactional
  public void revokeAllForUser(String userId) {
    for (Certificate certificate :
        certificateRepository.findByUserIdAndStatus(userId, Certificate.Status.VALID)) {
      try {
        revoke(certificate.getId());
      } catch (ApiException e) {
        // best effort on delete
      }
    }
  }

  /**
   * Re-verifies a revoked certificate: the PKI index entry is flipped back to valid and the CRL is
   * regenerated so the certificate is accepted by the VPN server again.
   */
  @Transactional
  public Certificate restore(String certificateId) {
    Certificate certificate = require(certificateId);
    if (certificate.getStatus() != Certificate.Status.REVOKED) {
      throw ApiException.conflict("not_revoked", "Only revoked certificates can be restored");
    }
    easyRsaService.unrevokeCert(certificate.getSerial(), certificate.getCommonName());
    certificate.setStatus(Certificate.Status.VALID);
    certificate.setRevokedAt(null);
    auditLogService.record(
        "CERT_RESTORE",
        AuditLogService.CAT_CERT,
        certificate.getId(),
        "certificate",
        Map.of("commonName", certificate.getCommonName()));
    return certificateRepository.save(certificate);
  }

  /**
   * Rotates a user's certificate: the current valid certificate is revoked, the on-disk artifacts
   * are replaced with a freshly issued one (same common name) and the bookkeeping row is updated
   * with the new serial and expiry.
   */
  @Transactional
  public Certificate rotate(String userId) {
    List<Certificate> valid =
        certificateRepository.findByUserIdAndStatus(userId, Certificate.Status.VALID);
    if (valid.isEmpty()) {
      throw ApiException.notFound(
          "no_valid_certificate", "User has no valid certificate to rotate");
    }
    Certificate certificate = valid.get(0);
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> ApiException.notFound("user_not_found", "User not found"));
    revoke(certificate.getId());
    easyRsaService.deleteClientCert(user.getUsername());
    easyRsaService.issueClientCert(user.getUsername());
    certificate.setStatus(Certificate.Status.VALID);
    certificate.setRevokedAt(null);
    certificate.setIssuedAt(Instant.now());
    certificate.setSerial(serialFor(user.getUsername()));
    certificate.setExpiresAt(expiryFor(user.getUsername()));
    auditLogService.record(
        "CERT_ROTATE",
        AuditLogService.CAT_CERT,
        certificate.getId(),
        "certificate",
        Map.of("commonName", certificate.getCommonName(), "userId", userId));
    return certificateRepository.save(certificate);
  }

  /** Marks valid certificates that have passed their expiry date as expired; runs daily. */
  @Scheduled(cron = "0 20 3 * * *")
  @Transactional
  public void markExpiredCertificates() {
    List<Certificate> expired =
        certificateRepository.findByStatusAndExpiresAtBefore(
            Certificate.Status.VALID, Instant.now());
    if (expired.isEmpty()) {
      return;
    }
    expired.forEach(c -> c.setStatus(Certificate.Status.EXPIRED));
    certificateRepository.saveAll(expired);
    log.info("Marked {} certificates as expired", expired.size());
  }

  /** Valid certificates expiring within the next {@value #EXPIRY_WARNING_DAYS} days. */
  @Transactional(readOnly = true)
  public List<Certificate> expiringSoon() {
    Instant now = Instant.now();
    return certificateRepository.findByStatusAndExpiresAtBetween(
        Certificate.Status.VALID, now, now.plus(EXPIRY_WARNING_DAYS, ChronoUnit.DAYS));
  }

  @Transactional(readOnly = true)
  public List<Certificate> list() {
    return certificateRepository.findAll();
  }

  @Transactional(readOnly = true)
  public Certificate get(String certificateId) {
    return require(certificateId);
  }

  private Certificate require(String certificateId) {
    return certificateRepository
        .findById(certificateId)
        .orElseThrow(() -> ApiException.notFound("certificate_not_found", "Certificate not found"));
  }

  private String serialFor(String commonName) {
    return easyRsaService.index().stream()
        .filter(e -> commonName.equals(e.commonName()) && e.status() == CertIndexEntry.Status.VALID)
        .map(CertIndexEntry::serial)
        .findFirst()
        .orElse(null);
  }

  private Instant expiryFor(String commonName) {
    return easyRsaService.index().stream()
        .filter(e -> commonName.equals(e.commonName()) && e.status() == CertIndexEntry.Status.VALID)
        .map(CertIndexEntry::expiry)
        .findFirst()
        .orElse(null);
  }
}
