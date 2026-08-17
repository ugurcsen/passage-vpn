package com.passagevpn.profile;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Profile token repository. */
public interface ProfileTokenRepository extends JpaRepository<ProfileToken, String> {

  Optional<ProfileToken> findByToken(String token);
}
