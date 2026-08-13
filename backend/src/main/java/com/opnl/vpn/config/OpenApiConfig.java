package com.opnl.vpn.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger configuration: exposes the JWT bearer scheme so Swagger UI shows an "Authorize"
 * button. Tokens are obtained from {@code POST /api/auth/login} (or {@code /api/auth/mfa} when the
 * account has TOTP enabled) and pasted as {@code Bearer <token>}.
 */
@Configuration
public class OpenApiConfig {

  public static final String BEARER_AUTH = "bearerAuth";

  @Bean
  public OpenAPI openApi(OpnlProperties opnlProperties) {
    return new OpenAPI()
        .info(
            new Info()
                .title(opnlProperties.brandName())
                .description(
                    "REST API of the OpenVPN management panel. "
                        + "Authenticate first via POST /api/auth/login; use the returned "
                        + "accessToken in the Authorize dialog.")
                .version("v1"))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_AUTH,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "Paste the access token returned by /api/auth/login (or /api/auth/mfa) "
                                + "as Bearer <token>.")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
  }
}
