package com.passagevpn.api.admin;

import com.passagevpn.common.ApiException;
import com.passagevpn.monitor.ConnectionLogService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin connection history: persisted sessions, newest first (scoped for GROUP_ADMIN). */
@RestController
@RequestMapping("/api/admin/connection-logs")
@PreAuthorize("hasAnyRole('ADMIN', 'GROUP_ADMIN')")
@Tag(
    name = "Admin - Connection Logs",
    description = "Persisted connection history (ADMIN/GROUP_ADMIN)")
public class ConnectionLogAdminController {

  private final ConnectionLogService connectionLogService;
  private final UserRepository userRepository;

  public ConnectionLogAdminController(
      ConnectionLogService connectionLogService, UserRepository userRepository) {
    this.connectionLogService = connectionLogService;
    this.userRepository = userRepository;
  }

  @GetMapping
  public List<ConnectionLogDto> list(
      Authentication authentication, @RequestParam(defaultValue = "25") int limit) {
    User actor =
        userRepository
            .findById(authentication.getPrincipal().toString())
            .orElseThrow(
                () -> ApiException.unauthorized("unauthorized", "Authentication required"));
    return connectionLogService.recent(actor, limit);
  }
}
