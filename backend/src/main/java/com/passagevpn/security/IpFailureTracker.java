package com.passagevpn.security;

import com.passagevpn.config.PassageProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sliding-window failure tracking per source IP for the OpenVPN auth flows. The per-IP budget
 * mirrors the account-level lockout settings ({@code opnl.auth.lockout-*}): too many failed connect
 * attempts from one IP within the window block that IP for the lockout duration. This throttles
 * distributed password/OTP guessing across many usernames that a single account would never notice.
 */
@Component
public class IpFailureTracker {

  private final int maxFailures;
  private final int windowSeconds;
  private final int blockSeconds;

  private record Attempt(long firstFailureAt, int count, long blockedUntil) {}

  private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

  public IpFailureTracker(PassageProperties properties) {
    PassageProperties.Auth cfg = properties.auth();
    this.maxFailures = cfg.lockoutMaxAttempts();
    this.windowSeconds = cfg.lockoutWindowSeconds();
    this.blockSeconds = cfg.lockoutDurationSeconds();
  }

  /** True when the IP is currently blocked (or already within a failure window that exceeds it). */
  public boolean isBlocked(String ip) {
    if (ip == null || ip.isBlank()) {
      return false;
    }
    prune(ip);
    Attempt attempt = attempts.get(ip);
    return attempt != null && attempt.blockedUntil > 0;
  }

  /** Registers a failed attempt for the IP; blocks it once the window budget is exhausted. */
  public void recordFailure(String ip) {
    if (ip == null || ip.isBlank()) {
      return;
    }
    long now = System.currentTimeMillis() / 1000;
    attempts.compute(
        ip,
        (key, attempt) -> {
          if (attempt == null || now - attempt.firstFailureAt > windowSeconds) {
            return new Attempt(now, 1, 0);
          }
          int count = attempt.count + 1;
          if (count >= maxFailures) {
            return new Attempt(attempt.firstFailureAt, count, now + blockSeconds);
          }
          return new Attempt(attempt.firstFailureAt, count, 0);
        });
  }

  /** Drops the failure history for the IP (e.g. after a successful authentication). */
  public void reset(String ip) {
    if (ip == null || ip.isBlank()) {
      return;
    }
    attempts.remove(ip);
  }

  private void prune(String ip) {
    long now = System.currentTimeMillis() / 1000;
    attempts.computeIfPresent(
        ip,
        (key, attempt) -> {
          if (attempt.blockedUntil > 0 && attempt.blockedUntil <= now) {
            return null;
          }
          if (attempt.blockedUntil == 0 && now - attempt.firstFailureAt > windowSeconds) {
            return null;
          }
          return attempt;
        });
  }
}
