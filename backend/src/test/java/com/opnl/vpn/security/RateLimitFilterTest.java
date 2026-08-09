package com.opnl.vpn.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

  private RateLimitFilter filter;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    filter = new RateLimitFilter(2, Duration.ofSeconds(60), objectMapper);
  }

  @Test
  void allowsRequestsUpToLimitThenReturns429() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    for (int i = 0; i < 2; i++) {
      filter.doFilter(
          request("/api/auth/login"), new MockHttpServletResponse(), new MockFilterChain());
    }
    filter.doFilter(request("/api/auth/login"), response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isNotBlank();
    assertThat(response.getContentAsString()).contains("rate_limited");
  }

  @Test
  void bucketsAreKeyedByIp() throws Exception {
    filter.doFilter(request("/api/auth/mfa"), new MockHttpServletResponse(), new MockFilterChain());
    MockHttpServletRequest other = request("/api/auth/mfa");
    other.setRemoteAddr("10.0.0.99");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(other, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void ignoresNonSensitivePaths() throws Exception {
    MockHttpServletRequest request = request("/api/setup/state");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void usesForwardedForHeaderWhenPresent() throws Exception {
    MockHttpServletRequest request = request("/api/auth/login");
    request.setRemoteAddr("172.17.0.1");
    request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.2");
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    MockHttpServletRequest sameIp = request("/api/auth/login");
    sameIp.addHeader("X-Forwarded-For", "203.0.113.7");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(sameIp, response, new MockFilterChain());
    filter.doFilter(sameIp, response, new MockFilterChain());
    filter.doFilter(sameIp, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(429);
  }

  private MockHttpServletRequest request(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.setRequestURI(uri);
    request.setRemoteAddr("127.0.0.1");
    return request;
  }
}
