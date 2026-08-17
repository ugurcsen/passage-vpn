package com.passagevpn.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts a bearer access token, validates it and populates the SecurityContext. MFA challenge
 * tokens are rejected here because they carry no role and must be redeemed at /api/auth/mfa.
 * Registered in the security filter chain by {@code SecurityConfig}.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;

  public JwtAuthFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      Claims claims = jwtService.parse(header.substring(BEARER_PREFIX.length()));
      if (claims != null && !jwtService.isMfaChallenge(claims)) {
        String role = claims.get("role", String.class);
        if (role != null) {
          var authentication =
              new UsernamePasswordAuthenticationToken(
                  claims.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
          SecurityContextHolder.getContext().setAuthentication(authentication);
        }
      }
    }
    filterChain.doFilter(request, response);
  }
}
