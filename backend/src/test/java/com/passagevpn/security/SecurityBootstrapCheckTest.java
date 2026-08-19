package com.passagevpn.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.passagevpn.config.PassageProperties;
import org.junit.jupiter.api.Test;

/** Startup fail-fast checks for security-critical configuration. */
class SecurityBootstrapCheckTest {

  private PassageProperties props(String token, String mgmtPassword) {
    PassageProperties.Jwt jwt = new PassageProperties.Jwt("secret-secret-secret-secret", 300, 14);
    PassageProperties.Auth auth = new PassageProperties.Auth("local", 5, 300, 900, 30, 60, null);
    PassageProperties.OpenVpn openvpn =
        new PassageProperties.OpenVpn(
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
            730,
            1194,
            1194,
            1195,
            1195);
    return new PassageProperties("./data", "OpenVPN Panel", token, jwt, auth, openvpn);
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
        .hasMessageContaining("PASSAGE_INTERNAL_TOKEN");
  }

  @Test
  void failsOnDefaultPlaceholderToken() {
    assertThatThrownBy(
            () ->
                new SecurityBootstrapCheck(
                        props(PassageProperties.DEFAULT_INTERNAL_TOKEN, "mgmt-secret"))
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
        .hasMessageContaining("PASSAGE_OPENVPN_MGMT_PASSWORD");
  }
}
