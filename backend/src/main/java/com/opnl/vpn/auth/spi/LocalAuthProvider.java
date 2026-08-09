package com.opnl.vpn.auth.spi;

import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Default credential backend: verifies against the locally stored BCrypt hash. */
@Component
public class LocalAuthProvider implements AuthProvider {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public LocalAuthProvider(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public String id() {
    return "local";
  }

  @Override
  public boolean verifyCredentials(String username, String password) {
    Optional<User> user = userRepository.findByUsername(username == null ? "" : username.trim());
    return user.isPresent()
        && user.get().getPasswordHash() != null
        && passwordEncoder.matches(password, user.get().getPasswordHash());
  }
}
