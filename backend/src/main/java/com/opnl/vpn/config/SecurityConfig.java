package com.opnl.vpn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.security.InternalTokenFilter;
import com.opnl.vpn.security.JwtAuthFilter;
import com.opnl.vpn.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Spring Security configuration: stateless, bearer-token API security. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private final OpnlProperties opnlProperties;
  private final JwtService jwtService;
  private final ObjectMapper objectMapper;

  public SecurityConfig(
      OpnlProperties opnlProperties, JwtService jwtService, ObjectMapper objectMapper) {
    this.opnlProperties = opnlProperties;
    this.jwtService = jwtService;
    this.objectMapper = objectMapper;
  }

  @Bean
  public JwtAuthFilter jwtAuthFilter() {
    return new JwtAuthFilter(jwtService);
  }

  /** Paths that never require authentication. */
  private static final String[] PUBLIC_PATHS = {
    "/api/auth/login",
    "/api/auth/refresh",
    "/api/auth/mfa",
    "/api/setup/**",
    "/internal/**",
    "/actuator/health",
    "/actuator/info",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/ws/**",
    "/error",
  };

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> auth.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(
                    (request, response, authException) -> {
                      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                      response.setContentType("application/json");
                      response
                          .getWriter()
                          .write(
                              objectMapper.writeValueAsString(
                                  com.opnl.vpn.common.ApiError.of(
                                      HttpServletResponse.SC_UNAUTHORIZED,
                                      "unauthorized",
                                      "Authentication required")));
                    }))
        .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new InternalTokenFilter(opnlProperties), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(List.of("*"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(false);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
