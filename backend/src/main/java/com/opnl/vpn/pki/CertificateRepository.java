package com.opnl.vpn.pki;

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
}
