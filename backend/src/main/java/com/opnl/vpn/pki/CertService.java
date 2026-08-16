package com.opnl.vpn.pki;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  /** Default certificate rotation policy when the server setting is unset. */
  public static final String DEFAULT_AUTO_ROTATE_POLICY = "notify";

  /** Default rotation horizon (days before expiry) when the server setting is unset. */
  public static final int DEFAULT_ROTATE_DAYS_BEFORE = 14;

  private final EasyRsaService easyRsaService;
  private final CertificateRepository certificateRepository;
  private final UserRepository userRepository;
  private final AuditLogService auditLogService;
  private final SettingsService settingsService;

  public CertService(
      EasyRsaService easyRsaService,
      CertificateRepository certificateRepository,
      UserRepository userRepository,
      AuditLogService auditLogService,
      SettingsService settingsService) {
    this.easyRsaService = easyRsaService;
    this.certificateRepository = certificateRepository;
    this.userRepository = userRepository;
    this.auditLogService = auditLogService;
    this.settingsService = settingsService;
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
      Certificate certificate = valid.get(0);
      if (easyRsaService.hasClientCert(certificate.getCommonName())) {
        return certificate;
      }
      // Bookkeeping row exists but the on-disk artifact is missing (e.g. demo-seeded rows or a
      // partially purged PKI): issue the real certificate on demand so profile downloads work.
      String cn = user.getUsername();
      easyRsaService.issueClientCert(cn);
      certificate.setCommonName(cn);
      certificate.setSerial(serialFor(cn));
      certificate.setExpiresAt(expiryFor(cn));
      certificate.setIssuedAt(Instant.now());
      auditLogService.record(
          "CERT_ISSUE",
          AuditLogService.CAT_CERT,
          certificate.getId(),
          "certificate",
          Map.of("commonName", cn));
      return certificateRepository.save(certificate);
    }
    String cn = user.getUsername();
    purgeStaleForCommonName(cn, userId);
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

  /**
   * Removes any certificate left behind by a previous account that used the same common name (e.g.
   * the old user was deleted without certificate cleanup). The stale cert is revoked so the CRL
   * rejects it, the on-disk artifacts are removed best-effort, and the bookkeeping row is deleted.
   * This keeps certificate issuance idempotent when a username is re-created.
   */
  private void purgeStaleForCommonName(String cn, String currentUserId) {
    Certificate stale = certificateRepository.findByCommonName(cn).orElse(null);
    if (stale == null || currentUserId.equals(stale.getUserId())) {
      return;
    }
    if (stale.getStatus() == Certificate.Status.VALID
        && easyRsaService.hasClientCert(stale.getCommonName())) {
      easyRsaService.revokeCert(stale.getCommonName());
    }
    try {
      easyRsaService.deleteClientCert(stale.getCommonName());
    } catch (ApiException ignored) {
      // best effort on re-issue
    }
    // Bulk delete executes immediately (a plain entity delete would be flushed AFTER the new
    // row's INSERT within the same transaction and hit the UNIQUE common_name constraint).
    certificateRepository.deleteByCommonNameAndUserIdNot(stale.getCommonName(), currentUserId);
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
   * Removes a user's certificates entirely (used when deleting an account with certificate
   * cleanup): any valid certificate is revoked so the CRL rejects it, the on-disk artifacts are
   * deleted best-effort, and the bookkeeping rows are removed.
   */
  @Transactional
  public void purgeForUser(String userId) {
    List<Certificate> certificates = certificateRepository.findByUserId(userId);
    if (certificates.isEmpty()) {
      return;
    }
    for (Certificate certificate : certificates) {
      try {
        if (certificate.getStatus() == Certificate.Status.VALID
            && easyRsaService.hasClientCert(certificate.getCommonName())) {
          easyRsaService.revokeCert(certificate.getCommonName());
        }
      } catch (ApiException ignored) {
        // best effort on delete
      }
      try {
        easyRsaService.deleteClientCert(certificate.getCommonName());
      } catch (ApiException ignored) {
        // best effort on delete
      }
      certificateRepository.delete(certificate);
    }
    auditLogService.record(
        "CERT_PURGE",
        AuditLogService.CAT_CERT,
        userId,
        "user",
        Map.of("count", certificates.size()));
  }

  /**
   * Removes a deleted account's certificate bookkeeping rows without touching the PKI artifacts.
   * Called on every user delete so re-creating the username never hits the UNIQUE common_name
   * constraint; the full PKI purge (revoke + delete artifacts) is opt-in via {@link #purgeForUser}.
   */
  @Transactional
  public void deleteRowsForUser(String userId) {
    certificateRepository.deleteByUserId(userId);
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

  /**
   * Applies the certificate rotation policy to certificates nearing expiry; runs daily after the
   * expiry scan. With policy {@code auto} every valid certificate expiring within the configured
   * horizon is rotated (revoke + reissue, same common name); {@code notify} and {@code off} only
   * report. Only certificates bound to an existing account are considered — orphaned rows and the
   * infrastructure server certificate are never rotated.
   */
  @Scheduled(cron = "0 35 3 * * *")
  @Transactional
  public void applyRotationPolicy() {
    if (!"auto".equalsIgnoreCase(rotationPolicy())) {
      return;
    }
    int daysBefore = rotationDaysBefore();
    Instant horizon = Instant.now().plus(daysBefore, ChronoUnit.DAYS);
    List<Certificate> candidates =
        certificateRepository.findByStatusAndExpiresAtBetween(
            Certificate.Status.VALID, Instant.now(), horizon);
    if (candidates.isEmpty()) {
      return;
    }
    int rotated = 0;
    int skipped = 0;
    for (Certificate candidate : candidates) {
      if (candidate.getUserId() == null || !userRepository.existsById(candidate.getUserId())) {
        skipped++;
        continue;
      }
      try {
        rotate(candidate.getUserId());
        auditLogService.record(
            "CERT_ROTATE_AUTO",
            AuditLogService.CAT_CERT,
            candidate.getId(),
            "certificate",
            Map.of("commonName", candidate.getCommonName(), "userId", candidate.getUserId()));
        rotated++;
      } catch (ApiException e) {
        skipped++;
        log.warn(
            "Skipped auto-rotation of certificate {} ({}): {}",
            candidate.getId(),
            candidate.getCommonName(),
            e.getMessage());
      }
    }
    log.info("Auto-rotation run: rotated {} certificates, skipped {}", rotated, skipped);
  }

  /**
   * The configured rotation policy: {@code off}, {@code notify} or {@code auto} (default notify).
   */
  public String rotationPolicy() {
    Object value = settingsService.serverSettings().get(SettingKeys.CERT_AUTO_ROTATE);
    return value instanceof String s && isPolicy(s) ? s.toLowerCase() : DEFAULT_AUTO_ROTATE_POLICY;
  }

  /** The configured rotation horizon in days, clamped to a sane range (default 14). */
  public int rotationDaysBefore() {
    Object value = settingsService.serverSettings().get(SettingKeys.CERT_ROTATE_DAYS_BEFORE);
    if (value instanceof Number n) {
      return Math.max(1, Math.min(3650, n.intValue()));
    }
    if (value instanceof String s) {
      try {
        return Math.max(1, Math.min(3650, Integer.parseInt(s.trim())));
      } catch (NumberFormatException ignored) {
        // fall through to default
      }
    }
    return DEFAULT_ROTATE_DAYS_BEFORE;
  }

  private static boolean isPolicy(String value) {
    return "off".equalsIgnoreCase(value)
        || "notify".equalsIgnoreCase(value)
        || "auto".equalsIgnoreCase(value);
  }

  /**
   * Synchronizes the certificate bookkeeping table with the Easy-RSA index.txt (the source of
   * truth): rows are created for certificates present in the PKI but missing from the table,
   * existing rows are updated with the current status/serial/expiry/revoked-at, and no rows are
   * ever deleted.
   *
   * <p>Bookkeeping rows are unique per common name while the index can carry several entries per CN
   * (an old revoked certificate plus the current valid one); the strongest state wins (VALID &gt;
   * REVOKED &gt; EXPIRED). The infrastructure {@code server} certificate and phantom VALID entries
   * that have no on-disk certificate are skipped.
   */
  @Transactional
  public ReconcileResult reconcile() {
    List<CertIndexEntry> entries =
        easyRsaService.index().stream()
            .filter(e -> e.commonName() != null && !"server".equals(e.commonName()))
            .toList();
    Map<String, CertIndexEntry> representative = new LinkedHashMap<>();
    for (CertIndexEntry entry : entries) {
      CertIndexEntry existing = representative.get(entry.commonName());
      if (existing == null || stronger(entry, existing) == entry) {
        representative.put(entry.commonName(), entry);
      }
    }
    Map<String, Certificate> bySerial = new HashMap<>();
    Map<String, Certificate> byCommonName = new HashMap<>();
    for (Certificate row : certificateRepository.findAll()) {
      if (row.getSerial() != null && !row.getSerial().isBlank()) {
        bySerial.put(row.getSerial(), row);
      }
      byCommonName.put(row.getCommonName(), row);
    }
    int created = 0;
    int updated = 0;
    int skipped = 0;
    List<Certificate> changed = new ArrayList<>();
    for (CertIndexEntry entry : representative.values()) {
      Certificate row = bySerial.get(entry.serial());
      if (row == null) {
        row = byCommonName.get(entry.commonName());
      }
      if (row == null) {
        if (entry.status() == CertIndexEntry.Status.VALID
            && !easyRsaService.hasClientCert(entry.commonName())) {
          skipped++;
          continue;
        }
        changed.add(
            Certificate.builder()
                .id(UUID.randomUUID().toString())
                .commonName(entry.commonName())
                .userId(userIdFor(entry.commonName()))
                .status(mapStatus(entry.status()))
                .serial(entry.serial())
                .expiresAt(entry.expiry())
                .revokedAt(
                    entry.status() == CertIndexEntry.Status.REVOKED ? entry.revokedAt() : null)
                .build());
        created++;
        continue;
      }
      boolean touched = false;
      if (!Objects.equals(row.getSerial(), entry.serial())) {
        row.setSerial(entry.serial());
        touched = true;
      }
      if (!Objects.equals(row.getExpiresAt(), entry.expiry())) {
        row.setExpiresAt(entry.expiry());
        touched = true;
      }
      Certificate.Status status = mapStatus(entry.status());
      if (row.getStatus() != status) {
        row.setStatus(status);
        touched = true;
      }
      if (entry.status() == CertIndexEntry.Status.REVOKED) {
        if (row.getRevokedAt() == null) {
          row.setRevokedAt(entry.revokedAt() != null ? entry.revokedAt() : Instant.now());
          touched = true;
        }
      } else if (row.getRevokedAt() != null) {
        row.setRevokedAt(null);
        touched = true;
      }
      if (touched) {
        changed.add(row);
        updated++;
      }
    }
    certificateRepository.saveAll(changed);
    auditLogService.record(
        "CERT_RECONCILE",
        AuditLogService.CAT_CERT,
        null,
        "certificate",
        Map.of("created", created, "updated", updated, "skipped", skipped));
    return new ReconcileResult(created, updated, skipped);
  }

  private static CertIndexEntry stronger(CertIndexEntry a, CertIndexEntry b) {
    return strength(a.status()) <= strength(b.status()) ? a : b;
  }

  private static int strength(CertIndexEntry.Status status) {
    return switch (status) {
      case VALID -> 0;
      case REVOKED -> 1;
      case EXPIRED -> 2;
    };
  }

  private static Certificate.Status mapStatus(CertIndexEntry.Status status) {
    return switch (status) {
      case VALID -> Certificate.Status.VALID;
      case REVOKED -> Certificate.Status.REVOKED;
      case EXPIRED -> Certificate.Status.EXPIRED;
    };
  }

  private String userIdFor(String commonName) {
    return userRepository.findByUsername(commonName).map(User::getId).orElse(null);
  }

  /**
   * Outcome of {@link #reconcile()}: how many bookkeeping rows were created, updated or skipped.
   */
  public record ReconcileResult(int created, int updated, int skipped) {}

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
