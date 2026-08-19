package com.passagevpn.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Top-level application configuration bound from {@code opnl.*} properties. */
@Validated
@ConfigurationProperties(prefix = "passage")
public record PassageProperties(
    String dataDir, String brandName, String internalToken, Jwt jwt, Auth auth, OpenVpn openvpn) {

  public static final String DEFAULT_INTERNAL_TOKEN = "change-me-internal-token";

  public record Jwt(@NotBlank String secret, long accessTtlSeconds, long refreshTtlDays) {
    public Duration accessTtl() {
      return Duration.ofSeconds(accessTtlSeconds);
    }

    public Duration refreshTtl() {
      return Duration.ofDays(refreshTtlDays);
    }
  }

  public record Auth(
      String provider,
      int lockoutMaxAttempts,
      int lockoutWindowSeconds,
      int lockoutDurationSeconds,
      int rateLimitMaxRequests,
      int rateLimitWindowSeconds,
      Argon2 argon2) {

    public record Argon2(
        int memoryIterations, int memory, int parallelism, int saltLength, int hashLength) {
      public static Argon2 defaults() {
        return new Argon2(3, 65536, 4, 16, 32);
      }
    }
  }

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
      String easyrsaBin,
      String logDir,
      String mgmtPassword,
      int certExpireDays,
      int udpPortBase,
      int udpPortEnd,
      int tcpPortBase,
      int tcpPortEnd) {
    public String mgmtEndpoint() {
      return mgmtHost + ":" + mgmtPort;
    }

    /** The inclusive UDP host-published port range; defaults to a single port. */
    public int udpRangeStart() {
      return udpPortBase;
    }

    public int udpRangeEnd() {
      return Math.max(udpPortBase, udpPortEnd);
    }

    /** The inclusive TCP host-published port range; defaults to a single port. */
    public int tcpRangeStart() {
      return tcpPortBase;
    }

    public int tcpRangeEnd() {
      return Math.max(tcpPortBase, tcpPortEnd);
    }
  }
}
