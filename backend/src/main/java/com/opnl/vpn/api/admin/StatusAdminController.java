package com.opnl.vpn.api.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin live status: panel info and per-daemon health. */
@RestController
@RequestMapping("/api/admin/status")
@PreAuthorize("hasRole('ADMIN')")
public class StatusAdminController {

  private final StatusAdminService statusAdminService;

  public StatusAdminController(StatusAdminService statusAdminService) {
    this.statusAdminService = statusAdminService;
  }

  @GetMapping
  public ServerStatusDto status() {
    return statusAdminService.status();
  }
}
