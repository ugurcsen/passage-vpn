package com.opnl.vpn.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Standard error response body returned by the API. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
  private Instant timestamp;
  private int status;
  private String code;
  private String message;
  private Map<String, Object> details;

  public static ApiError of(ApiException ex) {
    return ApiError.builder()
        .timestamp(Instant.now())
        .status(ex.getStatus().value())
        .code(ex.getCode())
        .message(ex.getMessage())
        .details(ex.getDetails().isEmpty() ? null : ex.getDetails())
        .build();
  }

  public static ApiError of(int status, String code, String message) {
    return ApiError.builder()
        .timestamp(Instant.now())
        .status(status)
        .code(code)
        .message(message)
        .build();
  }

  public static ApiError of(int status, String code, String message, Map<String, Object> details) {
    return ApiError.builder()
        .timestamp(Instant.now())
        .status(status)
        .code(code)
        .message(message)
        .details(details)
        .build();
  }
}
