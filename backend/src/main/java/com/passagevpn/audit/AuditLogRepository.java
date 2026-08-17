package com.passagevpn.audit;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link AuditLog} rows. */
public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

  /**
   * Filtered search over the audit trail. All filters are optional; null parameters leave the
   * condition open. {@code actionPattern} / {@code actorPattern} carry the {@code %term%} wildcards
   * so the query stays portable (no dialect-specific {@code concat}).
   */
  @Query(
      """
      select a from AuditLog a
      where (:actionPattern is null or lower(a.action) like :actionPattern)
        and (:actor is null or a.actorId = :actor or lower(a.actorName) like :actorPattern)
        and (:from is null or a.createdAt >= :from)
        and (:to is null or a.createdAt <= :to)
      order by a.createdAt desc
      """)
  Page<AuditLog> search(
      @Param("actionPattern") String actionPattern,
      @Param("actor") String actor,
      @Param("actorPattern") String actorPattern,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);

  @Modifying
  @Query("delete from AuditLog a where a.createdAt < :cutoff")
  int deleteOlderThan(Instant cutoff);
}
