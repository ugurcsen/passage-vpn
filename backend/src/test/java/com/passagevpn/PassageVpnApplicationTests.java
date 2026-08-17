package com.passagevpn;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Smoke test: full application context boots with SQLite + Flyway. */
@SpringBootTest(
    properties = {
      "passage.jwt.secret=test-context-secret-that-is-at-least-32-bytes",
      "passage.internal-token=test-internal-token",
      "passage.openvpn.mgmt-password=test-mgmt-pass"
    })
class PassageVpnApplicationTests {

  @Autowired private ApplicationContext context;

  @Test
  void contextLoads() {
    assertThat(context).isNotNull();
    assertThat(context.containsBean("passageVpnApplication")).isTrue();
  }
}
