package com.opnl.vpn.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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
}
