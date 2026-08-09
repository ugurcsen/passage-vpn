package com.opnl.vpn;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Smoke test: full application context boots with SQLite + Flyway. */
@SpringBootTest(properties = "opnl.jwt.secret=test-context-secret-that-is-at-least-32-bytes")
class OpnlVpnApplicationTests {

  @Autowired private ApplicationContext context;

  @Test
  void contextLoads() {
    assertThat(context).isNotNull();
    assertThat(context.containsBean("opnlVpnApplication")).isTrue();
  }
}
