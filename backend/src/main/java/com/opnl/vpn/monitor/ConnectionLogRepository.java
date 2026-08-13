package com.opnl.vpn.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link ConnectionLog} rows. */
public interface ConnectionLogRepository extends JpaRepository<ConnectionLog, String> {

  Optional<ConnectionLog> findFirstByCommonNameAndDisconnectedAtIsNullOrderByConnectedAtDesc(
      String commonName);

  List<ConnectionLog> findAllByDisconnectedAtIsNull();

  List<ConnectionLog> findTop20ByOrderByConnectedAtDesc();

  @Modifying
  @Query(
      "delete from ConnectionLog c where c.disconnectedAt is not null and c.disconnectedAt < :cutoff")
  int deleteClosedBefore(Instant cutoff);
}
