package com.opnl.vpn.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link User} entities. */
public interface UserRepository extends JpaRepository<User, String> {
  Optional<User> findByUsername(String username);

  boolean existsByUsername(String username);

  long countByRole(User.Role role);

  Optional<User> findByStaticIp(String staticIp);

  Optional<User> findByStaticIpv6(String staticIpv6);
}
