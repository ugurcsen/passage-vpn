package com.opnl.vpn.api.admin;

import com.opnl.vpn.monitor.SystemInfoService;
import com.opnl.vpn.system.MaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin system metrics and maintenance actions (preflight, restart, daemon reload). */
@RestController
@RequestMapping("/api/admin/system")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - System", description = "Host system metrics and maintenance (admin-only)")
public class SystemInfoAdminController {

  private final SystemInfoService systemInfoService;
  private final MaintenanceService maintenanceService;

  public SystemInfoAdminController(
      SystemInfoService systemInfoService, MaintenanceService maintenanceService) {
    this.systemInfoService = systemInfoService;
    this.maintenanceService = maintenanceService;
  }

  @GetMapping
  public SystemInfoDto system() {
    return systemInfoService.systemInfo();
  }

  @PostMapping("/preflight")
  @Operation(summary = "Run the preflight safety checks before restart or reload")
  public PreflightResult preflight() {
    return maintenanceService.preflight();
  }

  @PostMapping("/restart-backend")
  @Operation(summary = "Gracefully restart the backend (container restarts via restart policy)")
  public RestartResult restartBackend() {
    return maintenanceService.restartBackend();
  }

  @PostMapping("/reload-daemons")
  @Operation(summary = "Reload every enabled OpenVPN daemon config via SIGHUP")
  public ReloadResult reloadDaemons() {
    return maintenanceService.reloadDaemons();
  }
}
