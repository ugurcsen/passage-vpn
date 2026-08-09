package com.opnl.vpn.auth.spi;

import org.springframework.stereotype.Component;

/** SAML/SSO credential backend (stub — selectable via config, not implemented). */
@Component
public class SamlAuthProvider extends StubAuthProvider {

  @Override
  public String id() {
    return "saml";
  }
}
