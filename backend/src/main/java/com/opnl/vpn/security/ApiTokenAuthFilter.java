package com.opnl.vpn.security;

import com.opnl.vpn.token.ApiToken;
import com.opnl.vpn.token.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates API tokens for scripted automation. Tokens are accepted via the {@code X-API-Token}
 * header or a {@code Bearer} value carrying the {@code opnl_} prefix (JWT parsing never matches
 * that prefix, so the two flows cannot collide). Requests already authenticated by a JWT are left
 * untouched. Registered in the security filter chain by {@code SecurityConfig}.
 */
public class ApiTokenAuthFilter extends OncePerRequestFilter {

  private final ApiTokenService apiTokenService;

  public ApiTokenAuthFilter(ApiTokenService apiTokenService) {
    this.apiTokenService = apiTokenService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      String raw = rawToken(request);
      Optional<ApiToken> token = apiTokenService.authenticate(raw);
      if (token.isPresent()) {
        ApiToken apiToken = token.get();
        var authentication =
            new UsernamePasswordAuthenticationToken(
                apiToken.getId(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + apiToken.getRole())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    }
    filterChain.doFilter(request, response);
  }

  private static String rawToken(HttpServletRequest request) {
    String xToken = request.getHeader("X-API-Token");
    if (xToken != null && !xToken.isBlank()) {
      return xToken.trim();
    }
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      String candidate = authorization.substring(7).trim();
      if (candidate.startsWith(ApiTokenService.TOKEN_PREFIX)) {
        return candidate;
      }
    }
    return null;
  }
}
