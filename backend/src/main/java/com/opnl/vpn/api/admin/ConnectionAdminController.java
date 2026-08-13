package com.opnl.vpn.api.admin;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.monitor.MgmtClientManager;
import com.opnl.vpn.network.ConnectionRegistry;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin monitoring: live VPN connections tracked from internal script callbacks, plus session
 * termination through the management interface.
 */
@RestController
@RequestMapping("/api/admin/connections")
@PreAuthorize("hasRole('ADMIN')")
@Tag(
    name = "Admin - Connections",
    description = "Live VPN connections and session control (admin-only)")
public class ConnectionAdminController {

  private final ConnectionRegistry connectionRegistry;
  private final MgmtClientManager mgmtClientManager;

  public ConnectionAdminController(
      ConnectionRegistry connectionRegistry, MgmtClientManager mgmtClientManager) {
    this.connectionRegistry = connectionRegistry;
    this.mgmtClientManager = mgmtClientManager;
  }

  @GetMapping
  public List<ConnectionDto> list() {
    return connectionRegistry.sessions().stream().map(ConnectionDto::from).toList();
  }

  /** Terminates the session for a common name via the daemon management interface. */
  @PostMapping("/{commonName}/disconnect")
  public void disconnect(@PathVariable String commonName) {
    if (!mgmtClientManager.kill(commonName)) {
      throw ApiException.notFound("session_not_found", "No active session for " + commonName);
    }
  }
}
