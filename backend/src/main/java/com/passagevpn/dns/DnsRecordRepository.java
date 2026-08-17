package com.passagevpn.dns;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** DNS override repository. */
public interface DnsRecordRepository extends JpaRepository<DnsRecord, String> {

  List<DnsRecord> findByEnabledTrue();

  Optional<DnsRecord> findByHostnameIgnoreCase(String hostname);
}
