package com.passagevpn.security;

import com.passagevpn.config.PassageProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fails the application startup when security-critical configuration is missing or still on its
 * insecure default. Both the shared internal token (agent / script callbacks) and the local OpenVPN
 * management interface password are mandatory; the built-in {@code change-me} token is rejected so
 * a fresh install cannot silently run with a well-known secret.
 */
@Component
public class SecurityBootstrapCheck implements InitializingBean {

  private final PassageProperties properties;

  public SecurityBootstrapCheck(PassageProperties properties) {
    this.properties = properties;
  }

  @Override
  public void afterPropertiesSet() {
    String token = properties.internalToken();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException(
          "PASSAGE_INTERNAL_TOKEN is required: set it to a long random secret before starting");
    }
    if (PassageProperties.DEFAULT_INTERNAL_TOKEN.equals(token)) {
      throw new IllegalStateException(
          "PASSAGE_INTERNAL_TOKEN is still the insecure default '"
              + PassageProperties.DEFAULT_INTERNAL_TOKEN
              + "': change it before starting");
    }
    String mgmtPassword = properties.openvpn().mgmtPassword();
    if (mgmtPassword == null || mgmtPassword.isBlank()) {
      throw new IllegalStateException(
          "PASSAGE_OPENVPN_MGMT_PASSWORD is required: set it before starting "
              + "(also write the same value into the openvpn container's management password file)");
    }
  }
}
