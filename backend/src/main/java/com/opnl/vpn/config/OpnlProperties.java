package com.opnl.vpn.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Top-level application configuration bound from {@code opnl.*} properties. */
@Validated
@ConfigurationProperties(prefix = "opnl")
public record OpnlProperties(
    String dataDir, String brandName, String internalToken, Jwt jwt, Auth auth, OpenVpn openvpn) {

  public record Jwt(@NotBlank String secret, long accessTtlSeconds, long refreshTtlDays) {
    public Duration accessTtl() {
      return Duration.ofSeconds(accessTtlSeconds);
    }

    public Duration refreshTtl() {
      return Duration.ofDays(refreshTtlDays);
    }
  }

  public record Auth(
      int lockoutMaxAttempts, int lockoutWindowSeconds, int lockoutDurationSeconds) {}

  public record OpenVpn(
      String mgmtHost,
      int mgmtPort,
      String adminHost,
      String pkiDir,
      String ccdDir,
      String configDir,
      String scriptsDir,
      String scriptsSrcDir,
      String internalBaseUrl,
      String easyrsaBin) {
    public String mgmtEndpoint() {
      return mgmtHost + ":" + mgmtPort;
    }
  }
}
