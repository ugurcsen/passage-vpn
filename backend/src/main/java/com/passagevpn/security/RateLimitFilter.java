package com.passagevpn.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP token-bucket rate limiting for credential endpoints (login, MFA, refresh, VPN verify).
 * Slows down brute force and token-guessing attempts; emits 429 + {@code Retry-After} on overflow.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final List<String> SENSITIVE_PATHS =
      List.of(
          "/api/auth/login",
          "/api/auth/mfa",
          "/api/auth/refresh",
          "/internal/auth/verify",
          "/internal/auth/verify-otp",
          "/internal/seed-admin",
          "/internal/seed-demo");
  private static final long IDLE_PURGE_MS = Duration.ofMinutes(10).toMillis();
  private static final int MAX_BUCKETS = 10_000;

  private static final class TimedBucket {
    final Bucket bucket;
    volatile long lastAccessMillis;

    TimedBucket(Bucket bucket) {
      this.bucket = bucket;
      this.lastAccessMillis = System.currentTimeMillis();
    }
  }

  private final ConcurrentHashMap<String, TimedBucket> buckets = new ConcurrentHashMap<>();
  private final int maxRequests;
  private final Duration window;
  private final ObjectMapper objectMapper;

  public RateLimitFilter(int maxRequests, Duration window, ObjectMapper objectMapper) {
    this.maxRequests = maxRequests;
    this.window = window;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !SENSITIVE_PATHS.contains(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String ip = clientIp(request);
    TimedBucket timed = buckets.computeIfAbsent(ip, k -> new TimedBucket(newBucket()));
    timed.lastAccessMillis = System.currentTimeMillis();
    purgeIfNeeded();

    ConsumptionProbe probe = timed.bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      filterChain.doFilter(request, response);
      return;
    }
    long retryAfterSeconds =
        Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            objectMapper.writeValueAsString(
                com.passagevpn.common.ApiError.of(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "rate_limited",
                    "Too many requests; try again later",
                    Map.of("retryAfterSeconds", retryAfterSeconds))));
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(Bandwidth.classic(maxRequests, Refill.intervally(maxRequests, window)))
        .build();
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  /** Bounded memory: drop long-idle IP buckets when the map grows too large. */
  private void purgeIfNeeded() {
    if (buckets.size() < MAX_BUCKETS) {
      return;
    }
    long cutoff = System.currentTimeMillis() - IDLE_PURGE_MS;
    buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessMillis < cutoff);
  }
}
