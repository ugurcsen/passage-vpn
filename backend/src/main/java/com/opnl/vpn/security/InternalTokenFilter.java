package com.opnl.vpn.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.common.ApiError;
import com.opnl.vpn.config.OpnlProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects /internal/** script-facing endpoints with a shared secret header (X-Internal-Token).
 * No-op when the configured token is blank.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

  private final String expectedToken;

  /** Supports the java.time.Instant timestamp on {@link ApiError}. */
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public InternalTokenFilter(OpnlProperties properties) {
    this.expectedToken = properties.internalToken();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/")
        || expectedToken == null
        || expectedToken.isBlank();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String provided = request.getHeader("X-Internal-Token");
    if (!expectedToken.equals(provided)) {
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
