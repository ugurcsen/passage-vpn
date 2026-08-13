package com.opnl.vpn.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin configuration report: settings snapshot + PKI inventory + versions. */
@RestController
@RequestMapping("/api/admin/config-report")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Config Report", description = "Configuration snapshot (admin-only)")
public class ConfigReportAdminController {

  private final ConfigReportService configReportService;

  public ConfigReportAdminController(ConfigReportService configReportService) {
    this.configReportService = configReportService;
  }

  @GetMapping
  @Operation(summary = "Full configuration report")
  public ConfigReportDto report() {
    return configReportService.report();
  }
}
