package com.passagevpn.token;

import com.passagevpn.audit.AuditLogService;
import com.passagevpn.common.ApiException;
import com.passagevpn.security.JwtService;
import com.passagevpn.user.User;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle of API tokens for automation. The raw token is shown exactly once at creation; only its
 * SHA-256 hash is stored (matching how refresh tokens are hashed). Tokens are validated on every
 * authenticated request and may be revoked by deleting them.
 */
@Service
public class ApiTokenService {

  /** Prefix that marks a raw value as an API token (also makes a value never parse as a JWT). */
  public static final String TOKEN_PREFIX = "passage_";

  /** Displays the raw token with a masked tail in DTOs. */
  public static final String MASKED_TAIL = "…";

  /** Last-used timestamps are written at most this often to keep request load low. */
  private static final Duration LAST_USED_THROTTLE = Duration.ofSeconds(60);

  /**
   * Authenticated requests hit the token DB row on every call; tokens change rarely, so lookups are
   * cached for a short TTL. Create/delete clears the cache; revocation therefore takes effect
   * within the TTL window at worst.
   */
  private static final Duration AUTH_CACHE_TTL = Duration.ofSeconds(60);

  private final ApiTokenRepository repository;
  private final AuditLogService auditLogService;
  private final ConcurrentHashMap<String, CacheEntry> authCache = new ConcurrentHashMap<>();

  public ApiTokenService(ApiTokenRepository repository, AuditLogService auditLogService) {
    this.repository = repository;
    this.auditLogService = auditLogService;
  }

  /** Creates a token and returns the DTO plus the one-time raw value. */
  @Transactional
  public ApiTokenCreated create(String label, User.Role role, Instant expiresAt, String createdBy) {
    String trimmed = label == null ? "" : label.trim();
    if (trimmed.isBlank()) {
      throw ApiException.badRequest("label_required", "Token label is required");
    }
    User.Role effectiveRole = role == null ? User.Role.ADMIN : role;
    if (effectiveRole != User.Role.ADMIN) {
      throw ApiException.badRequest("invalid_role", "API tokens must carry the ADMIN role");
    }
    if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
      throw ApiException.badRequest(
          "invalid_expiry", "Token expiry must be in the future or omitted for no expiry");
    }
    String raw = newToken();
    Instant now = Instant.now();
    ApiToken token =
        ApiToken.builder()
            .id(UUID.randomUUID().toString())
            .label(trimmed)
            .tokenHash(JwtService.hash(raw))
            .prefix(raw.substring(0, Math.min(raw.length(), 14)))
            .role(effectiveRole.name())
            .expiresAt(expiresAt)
            .createdBy(createdBy)
            .createdAt(now)
            .build();
    repository.save(token);
    auditLogService.record(
        "API_TOKEN_CREATE",
        AuditLogService.CAT_API,
        token.getId(),
        "api_token",
        Map.of("label", token.getLabel(), "role", token.getRole()));
    authCache.clear();
    return new ApiTokenCreated(token, raw);
  }

  @Transactional(readOnly = true)
  public List<ApiToken> list() {
    return repository.findAllByOrderByCreatedAtDesc();
  }

  @Transactional
  public void delete(String id) {
    if (!repository.existsById(id)) {
      throw ApiException.notFound("token_not_found", "API token not found");
    }
    repository.deleteById(id);
    auditLogService.record("API_TOKEN_DELETE", AuditLogService.CAT_API, id, "api_token", Map.of());
    authCache.clear();
  }

  /**
   * Resolves a raw credential to a live token. Returns empty for unknown, expired or non-token
   * values so the caller can fall through to the rest of the security chain.
   */
  @Transactional
  public Optional<ApiToken> authenticate(String raw) {
    if (raw == null || !raw.startsWith(TOKEN_PREFIX)) {
      return Optional.empty();
    }
    String hash = JwtService.hash(raw);
    Instant now = Instant.now();
    CacheEntry entry = authCache.get(hash);
    if (entry == null || entry.loadedAt().isBefore(now.minus(AUTH_CACHE_TTL))) {
      ApiToken token = repository.findByTokenHash(hash).orElse(null);
      if (token == null || token.expired(now)) {
        authCache.remove(hash);
        return Optional.empty();
      }
      entry = new CacheEntry(token, now);
      authCache.put(hash, entry);
    }
    ApiToken token = entry.token();
    if (token.getLastUsedAt() == null
        || token.getLastUsedAt().isBefore(now.minus(LAST_USED_THROTTLE))) {
      token.setLastUsedAt(now);
      repository.save(token);
    }
    return Optional.of(token);
  }

  /** A raw token value; never persisted in full. */
  static String newToken() {
    byte[] bytes = new byte[32];
    ThreadLocalRandom.current().nextBytes(bytes);
    return TOKEN_PREFIX + HexFormat.of().formatHex(bytes);
  }

  public record ApiTokenCreated(ApiToken token, String rawToken) {}

  /** Cached lookup result with its load time so the TTL can be honored. */
  private record CacheEntry(ApiToken token, Instant loadedAt) {}
}
