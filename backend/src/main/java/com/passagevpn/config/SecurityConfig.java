package com.passagevpn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.security.ApiTokenAuthFilter;
import com.passagevpn.security.InternalTokenFilter;
import com.passagevpn.security.JwtAuthFilter;
import com.passagevpn.security.JwtService;
import com.passagevpn.security.RateLimitFilter;
import com.passagevpn.token.ApiTokenService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
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

  private final PassageProperties passageProperties;
  private final JwtService jwtService;
  private final ObjectMapper objectMapper;
  private final ApiTokenService apiTokenService;

  public SecurityConfig(
      PassageProperties passageProperties,
      JwtService jwtService,
      ObjectMapper objectMapper,
      ApiTokenService apiTokenService) {
    this.passageProperties = passageProperties;
    this.jwtService = jwtService;
    this.objectMapper = objectMapper;
    this.apiTokenService = apiTokenService;
  }

  @Bean
  public JwtAuthFilter jwtAuthFilter() {
    return new JwtAuthFilter(jwtService);
  }

  @Bean
  public ApiTokenAuthFilter apiTokenAuthFilter() {
    return new ApiTokenAuthFilter(apiTokenService);
  }

  @Bean
  public RateLimitFilter rateLimitFilter() {
    return new RateLimitFilter(
        passageProperties.auth().rateLimitMaxRequests(),
        Duration.ofSeconds(passageProperties.auth().rateLimitWindowSeconds()),
        objectMapper);
  }

  /** Paths that never require authentication. */
  private static final String[] PUBLIC_PATHS = {
    "/api/auth/login",
    "/api/auth/refresh",
    "/api/auth/mfa",
    "/api/auth/mfa/enroll",
    "/api/auth/mfa/enroll/confirm",
    "/api/setup/**",
    "/api/public/**",
    "/internal/**",
    "/api/portal/share/**",
    "/share/**",
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
                                  com.passagevpn.common.ApiError.of(
                                      HttpServletResponse.SC_UNAUTHORIZED,
                                      "unauthorized",
                                      "Authentication required")));
                    }))
        .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(apiTokenAuthFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(rateLimitFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new InternalTokenFilter(passageProperties), UsernamePasswordAuthenticationFilter.class);
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
