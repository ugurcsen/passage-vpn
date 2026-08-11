package com.opnl.vpn.api.admin;

import com.opnl.vpn.monitor.SystemInfoService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin system metrics: host CPU, memory and disk usage. */
@RestController
@RequestMapping("/api/admin/system")
@PreAuthorize("hasRole('ADMIN')")
public class SystemInfoAdminController {

  private final SystemInfoService systemInfoService;

  public SystemInfoAdminController(SystemInfoService systemInfoService) {
    this.systemInfoService = systemInfoService;
  }

  @GetMapping
  public SystemInfoDto system() {
    return systemInfoService.systemInfo();
  }
}
