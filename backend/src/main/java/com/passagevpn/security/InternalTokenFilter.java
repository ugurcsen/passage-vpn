package com.passagevpn.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.common.ApiError;
import com.passagevpn.config.PassageProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects /internal/** script-facing endpoints with a shared secret header (X-Internal-Token).
 * Enforced unconditionally: a blank token or the built-in {@code change-me} placeholder is rejected
 * so a misconfigured deployment can never run with a well-known secret.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

  private final String expectedToken;

  /** Supports the java.time.Instant timestamp on {@link ApiError}. */
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public InternalTokenFilter(PassageProperties properties) {
    this.expectedToken = properties.internalToken();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (expectedToken == null
        || expectedToken.isBlank()
        || PassageProperties.DEFAULT_INTERNAL_TOKEN.equals(expectedToken)
        || !expectedToken.equals(request.getHeader("X-Internal-Token"))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(
          response.getWriter(),
          ApiError.of(401, "invalid_internal_token", "Invalid internal token"));
      return;
    }
    chain.doFilter(request, response);
  }
}
