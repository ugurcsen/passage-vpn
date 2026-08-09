package com.opnl.vpn.auth.spi;

import com.opnl.vpn.common.ApiException;
import lombok.extern.slf4j.Slf4j;

/**
 * Base for directory-provider stubs. Selecting one of these via {@code opnl.auth.provider} is
 * explicitly rejected at runtime until the integration is implemented.
 */
@Slf4j
abstract class StubAuthProvider implements AuthProvider {

  @Override
  public boolean verifyCredentials(String username, String password) {
    log.warn("Auth provider '{}' selected but not implemented; rejecting login", id());
    throw ApiException.internal(
        "provider_not_implemented",
        "The " + id().toUpperCase() + " auth provider is not implemented yet");
  }
}
