package com.passagevpn.auth.spi;

import org.springframework.stereotype.Component;

/** LDAP directory credential backend (stub — selectable via config, not implemented). */
@Component
public class LdapAuthProvider extends StubAuthProvider {

  @Override
  public String id() {
    return "ldap";
  }
}
