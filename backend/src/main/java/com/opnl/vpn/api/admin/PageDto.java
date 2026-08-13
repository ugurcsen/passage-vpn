package com.opnl.vpn.api.admin;

import java.util.List;
import org.springframework.data.domain.Page;

/** Generic paginated response wrapper used by the audit log API. */
public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

  public static <T> PageDto<T> of(Page<T> page) {
    return new PageDto<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
