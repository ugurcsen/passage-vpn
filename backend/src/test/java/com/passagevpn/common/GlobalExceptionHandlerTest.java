package com.passagevpn.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void noResourceMapsTo404() {
    ResponseEntity<ApiError> response =
        handler.handleNoResource(
            new NoResourceFoundException(HttpMethod.GET, "/api/admin/nonexistent"));
    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody().getCode()).isEqualTo("not_found");
  }

  @Test
  void invalidDataAccessMapsTo400() {
    ResponseEntity<ApiError> response =
        handler.handleInvalidDataAccess(new InvalidDataAccessApiUsageException("boom"));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody().getCode()).isEqualTo("bad_request");
  }

  @Test
  void typeMismatchMapsTo400() {
    MethodArgumentTypeMismatchException ex =
        new MethodArgumentTypeMismatchException(
            "user-locked", java.lang.Enum.class, "type", null, null);
    ResponseEntity<ApiError> response = handler.handleTypeMismatch(ex);
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody().getCode()).isEqualTo("invalid_parameter");
    assertThat(response.getBody().getMessage()).contains("user-locked").contains("type");
  }

  @Test
  void accessDeniedMapsTo403() {
    ResponseEntity<ApiError> response =
        handler.handleAccessDenied(new AccessDeniedException("denied"));
    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(response.getBody().getCode()).isEqualTo("forbidden");
  }
}
