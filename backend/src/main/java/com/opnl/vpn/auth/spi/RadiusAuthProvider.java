package com.opnl.vpn.auth.spi;

import org.springframework.stereotype.Component;

/** RADIUS credential backend (stub — selectable via config, not implemented). */
@Component
public class RadiusAuthProvider extends StubAuthProvider {

  @Override
  public String id() {
    return "radius";
  }
}
