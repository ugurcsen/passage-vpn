package com.opnl.vpn.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opnl.vpn.config.OpnlProperties;
import org.junit.jupiter.api.Test;

/** Startup fail-fast checks for security-critical configuration. */
class SecurityBootstrapCheckTest {

  private OpnlProperties props(String token, String mgmtPassword) {
    OpnlProperties.Jwt jwt = new OpnlProperties.Jwt("secret-secret-secret-secret", 300, 14);
    OpnlProperties.Auth auth = new OpnlProperties.Auth("local", 5, 300, 900, 30, 60);
    OpnlProperties.OpenVpn openvpn =
        new OpnlProperties.OpenVpn(
            "127.0.0.1",
            7505,
            "vpn.example.com",
            "/pki",
            "/ccd",
            "/config",
            "/scripts",
            "/scripts",
            "http://localhost",
            "easyrsa",
            "/logs",
            mgmtPassword,
            730);
    return new OpnlProperties("./data", "OpenVPN Panel", token, jwt, auth, openvpn);
  }

  @Test
  void passesWithRandomTokenAndPassword() {
    assertThatCode(
            () ->
                new SecurityBootstrapCheck(props("a-long-random-token", "mgmt-secret"))
                    .afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  void failsOnBlankToken() {
    assertThatThrownBy(
            () -> new SecurityBootstrapCheck(props("  ", "mgmt-secret")).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPNL_INTERNAL_TOKEN");
  }

  @Test
  void failsOnDefaultPlaceholderToken() {
    assertThatThrownBy(
            () ->
                new SecurityBootstrapCheck(
                        props(OpnlProperties.DEFAULT_INTERNAL_TOKEN, "mgmt-secret"))
                    .afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("insecure default");
  }

  @Test
  void failsOnMissingMgmtPassword() {
    assertThatThrownBy(
            () ->
                new SecurityBootstrapCheck(props("a-long-random-token", null)).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPNL_OPENVPN_MGMT_PASSWORD");
  }
}
