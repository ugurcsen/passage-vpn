package com.opnl.vpn.api.admin;

import com.opnl.vpn.network.ConnectionRegistry;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin monitoring: live VPN connections tracked from internal script callbacks. */
@RestController
@RequestMapping("/api/admin/connections")
@PreAuthorize("hasRole('ADMIN')")
public class ConnectionAdminController {

  private final ConnectionRegistry connectionRegistry;

  public ConnectionAdminController(ConnectionRegistry connectionRegistry) {
    this.connectionRegistry = connectionRegistry;
  }

  @GetMapping
  public List<ConnectionDto> list() {
    return connectionRegistry.sessions().stream().map(ConnectionDto::from).toList();
  }
}
