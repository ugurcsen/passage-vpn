package com.opnl.vpn.api.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin dashboard: aggregate counters and recent connections. */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardAdminController {

  private final DashboardAdminService dashboardAdminService;

  public DashboardAdminController(DashboardAdminService dashboardAdminService) {
    this.dashboardAdminService = dashboardAdminService;
  }

  @GetMapping
  public DashboardDto dashboard() {
    return dashboardAdminService.dashboard();
  }
}
