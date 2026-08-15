package com.opnl.vpn.security;

import com.opnl.vpn.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guards the bootstrap-only seed endpoints ({@code /internal/seed-admin}, {@code /internal/
 * seed-demo}) behind a second shared secret header {@code X-Bootstrap-Token}. Unlike the regular
 * internal token, this secret is not present in the OpenVPN container (scripts never call seed
 * endpoints), so it protects against a compromised gateway re-creating an admin account.
 *
 * <p>Optional: when {@code opnl.bootstrap-token} (env {@code OPNL_BOOTSTRAP_TOKEN}) is unset/blank
 * the check is a no-op so existing deployments keep working. Operators are encouraged to set it.
 */
@Component
public class SeedGuard {

  private final String bootstrapToken;

  public SeedGuard(@Value("${opnl.bootstrap-token:}") String bootstrapToken) {
    this.bootstrapToken = bootstrapToken;
  }

  /** Throws 403 unless the presented header matches the configured bootstrap token. */
  public void assertSeedAllowed(String bootstrapTokenHeader) {
    if (bootstrapToken == null || bootstrapToken.isBlank()) {
      return;
    }
    if (bootstrapTokenHeader == null || !bootstrapToken.equals(bootstrapTokenHeader)) {
      throw ApiException.forbidden(
          "bootstrap_token_required", "Bootstrap token missing or invalid");
    }
  }
}
