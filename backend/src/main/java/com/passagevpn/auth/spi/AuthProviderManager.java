package com.passagevpn.auth.spi;

import com.passagevpn.config.PassageProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves the active {@link AuthProvider} from {@code opnl.auth.provider}. Providers are
 * discovered via Spring and keyed by their {@link AuthProvider#id()}.
 */
@Slf4j
@Service
public class AuthProviderManager {

  private final Map<String, AuthProvider> providers;
  private final String activeProviderId;

  public AuthProviderManager(PassageProperties properties, List<AuthProvider> providers) {
    this.providers =
        providers.stream().collect(Collectors.toMap(AuthProvider::id, Function.identity()));
    this.activeProviderId = properties.auth().provider();
    if (!this.providers.containsKey(activeProviderId)) {
      throw new IllegalArgumentException(
          "Unknown auth provider '"
              + activeProviderId
              + "'; registered: "
              + this.providers.keySet());
    }
    if (!"local".equals(activeProviderId)) {
      log.warn(
          "Auth provider '{}' selected — remote providers are stubs and will reject logins",
          activeProviderId);
    }
  }

  /** The configured active provider. */
  public AuthProvider active() {
    return providers.get(activeProviderId);
  }
}
