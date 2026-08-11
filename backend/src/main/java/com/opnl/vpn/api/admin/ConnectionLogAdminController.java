package com.opnl.vpn.api.admin;

import com.opnl.vpn.monitor.ConnectionLogService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin connection history: persisted sessions, newest first. */
@RestController
@RequestMapping("/api/admin/connection-logs")
@PreAuthorize("hasRole('ADMIN')")
public class ConnectionLogAdminController {

  private final ConnectionLogService connectionLogService;

  public ConnectionLogAdminController(ConnectionLogService connectionLogService) {
    this.connectionLogService = connectionLogService;
  }

  @GetMapping
  public List<ConnectionLogDto> list(@RequestParam(defaultValue = "25") int limit) {
    return connectionLogService.recent(limit);
  }
}
