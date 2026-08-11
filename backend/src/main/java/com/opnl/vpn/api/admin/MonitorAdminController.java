package com.opnl.vpn.api.admin;

import com.opnl.vpn.monitor.MonitorService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin monitoring: full live snapshot (REST fallback for the WebSocket push). */
@RestController
@RequestMapping("/api/admin/monitor")
@PreAuthorize("hasRole('ADMIN')")
public class MonitorAdminController {

  private final MonitorService monitorService;

  public MonitorAdminController(MonitorService monitorService) {
    this.monitorService = monitorService;
  }

  @GetMapping
  public MonitorSnapshotDto snapshot() {
    return monitorService.latestSnapshot();
  }
}
