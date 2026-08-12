package com.opnl.vpn.pki;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Certificate bookkeeping repository. */
public interface CertificateRepository extends JpaRepository<Certificate, String> {

  Optional<Certificate> findByCommonName(String commonName);

  List<Certificate> findByUserId(String userId);

  List<Certificate> findByUserIdAndStatus(String userId, Certificate.Status status);

  boolean existsByCommonName(String commonName);

  long countByStatus(Certificate.Status status);

  /** Valid certificates that have already passed their expiry date. */
  List<Certificate> findByStatusAndExpiresAtBefore(Certificate.Status status, Instant cutoff);

  /** Valid certificates expiring within the given window (exclusive bounds). */
  List<Certificate> findByStatusAndExpiresAtBetween(
      Certificate.Status status, Instant from, Instant to);
}
