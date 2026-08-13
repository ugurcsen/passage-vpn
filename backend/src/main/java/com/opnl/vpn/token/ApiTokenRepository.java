package com.opnl.vpn.token;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepository extends JpaRepository<ApiToken, String> {

  Optional<ApiToken> findByTokenHash(String tokenHash);

  boolean existsByTokenHash(String tokenHash);

  List<ApiToken> findAllByOrderByCreatedAtDesc();
}
