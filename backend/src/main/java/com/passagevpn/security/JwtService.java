package com.passagevpn.security;

import com.passagevpn.config.PassageProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * JWT issuing and validation. Access tokens carry {@code sub} (user id), {@code role} and {@code
 * username}; pre-auth MFA tokens carry {@code mfa} instead of {@code role} and can only be
 * exchanged for real tokens at /api/auth/mfa.
 */
@Service
public class JwtService {

  private static final String ISSUER = "passage";
  private static final String MFA_CLAIM = "mfa";
  private static final String MFA_ENROLL_CLAIM = "mfa_enroll";

  private final PassageProperties properties;
  private final SecretKey key;

  public JwtService(PassageProperties properties) {
    this.properties = properties;
    String secret = properties.jwt().secret();
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "PASSAGE_JWT_SECRET must be at least 32 bytes (256 bits) long");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  public String issueAccessToken(String userId, String username, String role) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(ISSUER)
        .subject(userId)
        .claim("username", username)
        .claim("role", role)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(properties.jwt().accessTtl())))
        .signWith(key)
        .compact();
  }

  /** Short-lived token proving the first password factor succeeded. */
  public String issueMfaChallenge(String userId) {
    return issuePreAuthToken(userId, MFA_CLAIM);
  }

  /** Short-lived token proving the first factor succeeded and that TOTP enrollment is required. */
  public String issueMfaEnrollChallenge(String userId) {
    return issuePreAuthToken(userId, MFA_ENROLL_CLAIM);
  }

  private String issuePreAuthToken(String userId, String purposeClaim) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(ISSUER)
        .subject(userId)
        .id(UUID.randomUUID().toString())
        .claim(purposeClaim, true)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(300)))
        .signWith(key)
        .compact();
  }

  /** Parses and validates a token; returns null when invalid/expired. */
  public Claims parse(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      return Jwts.parser()
          .verifyWith(key)
          .requireIssuer(ISSUER)
          .build()
          .parseSignedClaims(token)
          .getPayload();
    } catch (Exception e) {
      return null;
    }
  }

  /** True when this token is an MFA challenge (not usable as an access token). */
  public boolean isMfaChallenge(Claims claims) {
    return claims != null && Boolean.TRUE.equals(claims.get(MFA_CLAIM, Boolean.class));
  }

  /** True when this token is an MFA enrollment challenge (not usable as an access token). */
  public boolean isMfaEnrollChallenge(Claims claims) {
    return claims != null && Boolean.TRUE.equals(claims.get(MFA_ENROLL_CLAIM, Boolean.class));
  }

  /** SHA-256 hex hash for storing refresh tokens server-side. */
  public static String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
