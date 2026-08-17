package com.passagevpn.auth.spi;

/**
 * Credential verification backend for web logins and OpenVPN auth-user-pass-verify.
 *
 * <p>Concrete providers are selected via {@code opnl.auth.provider}. {@code local} verifies against
 * the stored BCrypt hash; LDAP/RADIUS/SAML are registered as stubs and must be implemented against
 * their respective directory services.
 */
public interface AuthProvider {

  /** Stable provider id, e.g. {@code local}, {@code ldap}, {@code radius}, {@code saml}. */
  String id();

  /**
   * Verifies a username/password pair. For remote directory providers this would bind to the
   * external service; the local provider compares against the stored password hash.
   */
  boolean verifyCredentials(String username, String password);
}
