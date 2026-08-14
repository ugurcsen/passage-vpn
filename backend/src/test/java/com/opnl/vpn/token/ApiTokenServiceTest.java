package com.opnl.vpn.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.security.JwtService;
import com.opnl.vpn.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the API token lifecycle (create/list/delete/authenticate). */
class ApiTokenServiceTest {

  private ApiTokenRepository repository;
  private AuditLogService auditLogService;
  private ApiTokenService service;

  @BeforeEach
  void setUp() {
    repository = mock(ApiTokenRepository.class);
    auditLogService = mock(AuditLogService.class);
    service = new ApiTokenService(repository, auditLogService);
  }

  @Test
  void createReturnsOneTimeTokenAndStoresHash() {
    when(repository.save(any(ApiToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var created = service.create("ci-deploy", User.Role.ADMIN, null, "admin");

    assertThat(created.rawToken()).startsWith(ApiTokenService.TOKEN_PREFIX);
    var token = created.token();
    assertThat(token.getLabel()).isEqualTo("ci-deploy");
    assertThat(token.getRole()).isEqualTo("ADMIN");
    assertThat(token.getTokenHash())
        .isNotEqualTo(created.rawToken())
        .isEqualTo(JwtService.hash(created.rawToken()));
    verify(repository).save(token);
    verify(auditLogService)
        .record(
            eq("API_TOKEN_CREATE"),
            eq(AuditLogService.CAT_API),
            eq(token.getId()),
            eq("api_token"),
            any(Map.class));
  }

  @Test
  void createDefaultsRoleToAdmin() {
    when(repository.save(any(ApiToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var created = service.create("label", null, null, null);

    assertThat(created.token().getRole()).isEqualTo("ADMIN");
  }

  @Test
  void createRejectsBlankLabel() {
    assertThatThrownBy(() -> service.create("   ", null, null, null))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("label_required"));
  }

  @Test
  void createRejectsUserRole() {
    assertThatThrownBy(() -> service.create("label", User.Role.USER, null, null))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_role"));
  }

  @Test
  void createRejectsPastExpiry() {
    assertThatThrownBy(() -> service.create("label", null, Instant.now().minusSeconds(10), null))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_expiry"));
  }

  @Test
  void listReturnsNewestFirst() {
    var token = token("t1", "ci");
    when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(token));

    assertThat(service.list()).containsExactly(token);
  }

  @Test
  void deleteRemovesTokenAndAudits() {
    when(repository.existsById("t1")).thenReturn(true);

    service.delete("t1");

    verify(repository).deleteById("t1");
    verify(auditLogService)
        .record(
            eq("API_TOKEN_DELETE"),
            eq(AuditLogService.CAT_API),
            eq("t1"),
            eq("api_token"),
            any(Map.class));
  }

  @Test
  void deleteMissingTokenThrows() {
    when(repository.existsById("t1")).thenReturn(false);

    assertThatThrownBy(() -> service.delete("t1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("token_not_found"));
    verify(repository, never()).deleteById(any());
  }

  @Test
  void authenticateResolvesKnownToken() {
    String raw = ApiTokenService.newToken();
    var token = token("t1", "ADMIN");
    when(repository.findByTokenHash(JwtService.hash(raw))).thenReturn(Optional.of(token));

    var result = service.authenticate(raw);

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo("t1");
    assertThat(token.getLastUsedAt()).isNotNull();
    verify(repository).save(token);
  }

  @Test
  void authenticateRejectsUnknownToken() {
    when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThat(service.authenticate("opnl_0000")).isEmpty();
    verify(repository, never()).save(any());
  }

  @Test
  void authenticateRejectsExpiredToken() {
    String raw = ApiTokenService.newToken();
    var token = token("t1", "ADMIN");
    token.setExpiresAt(Instant.now().minusSeconds(1));
    when(repository.findByTokenHash(JwtService.hash(raw))).thenReturn(Optional.of(token));

    assertThat(service.authenticate(raw)).isEmpty();
    verify(repository, never()).save(any());
  }

  @Test
  void authenticateIgnoresNonTokenValues() {
    assertThat(service.authenticate("not-a-token")).isEmpty();
    assertThat(service.authenticate(null)).isEmpty();
    verify(repository, never()).findByTokenHash(any());
  }

  @Test
  void authenticateDoesNotUpdateLastUsedWithinThrottleWindow() {
    String raw = ApiTokenService.newToken();
    var token = token("t1", "ADMIN");
    token.setLastUsedAt(Instant.now());
    when(repository.findByTokenHash(JwtService.hash(raw))).thenReturn(Optional.of(token));

    service.authenticate(raw);

    verify(repository, never()).save(token);
  }

  @Test
  void authenticateCachesLookupWithinTtl() {
    String raw = ApiTokenService.newToken();
    var token = token("t1", "ADMIN");
    when(repository.findByTokenHash(JwtService.hash(raw))).thenReturn(Optional.of(token));

    assertThat(service.authenticate(raw)).isPresent();
    assertThat(service.authenticate(raw)).isPresent();

    verify(repository, times(1)).findByTokenHash(JwtService.hash(raw));
  }

  @Test
  void deleteClearsTheAuthenticateCache() {
    String raw = ApiTokenService.newToken();
    var token = token("t1", "ADMIN");
    when(repository.findByTokenHash(JwtService.hash(raw))).thenReturn(Optional.of(token));
    assertThat(service.authenticate(raw)).isPresent();
    when(repository.existsById("t1")).thenReturn(true);

    service.delete("t1");

    // Revocation must be visible immediately: the cached entry is dropped.
    when(repository.findByTokenHash(JwtService.hash(raw))).thenReturn(Optional.empty());
    assertThat(service.authenticate(raw)).isEmpty();
    verify(repository, times(2)).findByTokenHash(JwtService.hash(raw));
  }

  @Test
  void newTokenHasPrefixAndStableLength() {
    String raw = ApiTokenService.newToken();
    assertThat(raw).startsWith(ApiTokenService.TOKEN_PREFIX);
    assertThat(raw.length()).isEqualTo(ApiTokenService.TOKEN_PREFIX.length() + 64);
  }

  private static ApiToken token(String id, String role) {
    return ApiToken.builder()
        .id(id)
        .label("label")
        .tokenHash("hash")
        .prefix("opnl_abc")
        .role(role)
        .createdAt(Instant.now())
        .build();
  }
}
