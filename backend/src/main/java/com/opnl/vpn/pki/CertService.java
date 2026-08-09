package com.opnl.vpn.pki;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user certificate lifecycle: issues client certificates via Easy-RSA, keeps bookkeeping in
 * sync, revokes and downloads. The Easy-RSA index.txt remains the source of truth for status.
 */
@Service
public class CertService {

  private final EasyRsaService easyRsaService;
  private final CertificateRepository certificateRepository;
  private final UserRepository userRepository;

  public CertService(
      EasyRsaService easyRsaService,
      CertificateRepository certificateRepository,
      UserRepository userRepository) {
    this.easyRsaService = easyRsaService;
    this.certificateRepository = certificateRepository;
    this.userRepository = userRepository;
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

  @Transactional(readOnly = true)
  public List<Certificate> list() {
    return certificateRepository.findAll();
  }

  @Transactional(readOnly = true)
  public Certificate get(String certificateId) {
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
