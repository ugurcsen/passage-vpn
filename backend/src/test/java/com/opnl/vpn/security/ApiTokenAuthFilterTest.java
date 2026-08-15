package com.opnl.vpn.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.token.ApiToken;
import com.opnl.vpn.token.ApiTokenService;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests for API-token authentication in the filter chain. */
class ApiTokenAuthFilterTest {

  private ApiTokenService apiTokenService;
  private ApiTokenAuthFilter filter;

  @BeforeEach
  void setUp() {
    apiTokenService = mock(ApiTokenService.class);
    filter = new ApiTokenAuthFilter(apiTokenService);
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatesViaXApiTokenHeader() throws Exception {
    var token = token("t1", "ADMIN");
    when(apiTokenService.authenticate("opnl_raw")).thenReturn(Optional.of(token));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-API-Token", "opnl_raw");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getPrincipal()).isEqualTo("t1");
    assertThat(authentication.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN");
  }

  @Test
  void authenticatesViaBearerHeaderWithTokenPrefix() throws Exception {
    var token = token("t1", "GROUP_ADMIN");
    when(apiTokenService.authenticate("opnl_raw")).thenReturn(Optional.of(token));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer opnl_raw");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_GROUP_ADMIN");
  }

  @Test
  void doesNotTreatJwtBearerAsApiToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.some.jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(apiTokenService, never()).authenticate("eyJhbGciOiJIUzI1NiJ9.some.jwt");
  }

  @Test
  void leavesExistingJwtAuthenticationUntouched() throws Exception {
    var existing =
        new UsernamePasswordAuthenticationToken(
            "u1", null, java.util.List.of((GrantedAuthority) () -> "ROLE_ADMIN"));
    SecurityContextHolder.getContext().setAuthentication(existing);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-API-Token", "opnl_raw");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(apiTokenService, never()).authenticate("opnl_raw");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
    verify(chain).doFilter(request, response);
  }

  @Test
  void unknownTokenLeavesNoAuthentication() throws Exception {
    when(apiTokenService.authenticate("opnl_unknown")).thenReturn(Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-API-Token", "opnl_unknown");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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
