package com.opnl.vpn.api.admin;

import com.opnl.vpn.internal.InternalTlsService;
import com.opnl.vpn.network.NodeRegistryService;
import com.opnl.vpn.network.NodeRegistryService.NodeRequest;
import com.opnl.vpn.network.OpenVpnNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin management of registered VPN gateway nodes (admin-only). */
@RestController
@RequestMapping("/api/admin/nodes")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Nodes", description = "VPN gateway node registry (admin-only)")
public class NodeAdminController {

  private final NodeRegistryService nodeRegistryService;
  private final InternalTlsService tlsService;

  public NodeAdminController(
      NodeRegistryService nodeRegistryService, InternalTlsService tlsService) {
    this.nodeRegistryService = nodeRegistryService;
    this.tlsService = tlsService;
  }

  @GetMapping
  public List<OpenVpnNodeDto> list() {
    return nodeRegistryService.list();
  }

  @PostMapping
  public OpenVpnNodeDto create(@Valid @RequestBody NodeRequest request) {
    return nodeRegistryService.create(request);
  }

  @PutMapping("/{id}")
  public OpenVpnNodeDto update(@PathVariable String id, @Valid @RequestBody NodeRequest request) {
    return nodeRegistryService.update(id, request);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    nodeRegistryService.delete(id);
  }

  @PostMapping("/{id}/enabled")
  public OpenVpnNodeDto setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
    return nodeRegistryService.setEnabled(id, enabled);
  }

  /**
   * Issues (or re-issues) the mTLS client certificate the node's agent presents to the internal
   * control plane. The bundle must be installed on the remote gateway as {@code opnl.agent.tls-*}.
   */
  @PostMapping("/{id}/agent-cert")
  public AgentCertResult issueAgentCert(@PathVariable String id) {
    OpenVpnNode node = nodeRegistryService.requireNode(id);
    InternalTlsService.AgentCertificate cert = tlsService.issueAgentCert(node.getName(), null);
    return new AgentCertResult(
        cert.nodeName(),
        cert.caCertPem(),
        cert.certPem(),
        cert.keyPem(),
        cert.pkcs12(),
        cert.password());
  }

  public record AgentCertResult(
      String nodeName, String caCert, String cert, String key, byte[] pkcs12, String password) {}
}
