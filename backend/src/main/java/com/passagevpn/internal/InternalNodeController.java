package com.passagevpn.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.passagevpn.common.ApiException;
import com.passagevpn.config.InternalProperties;
import com.passagevpn.network.NodeRegistryService;
import com.passagevpn.network.OpenVpnNode;
import com.passagevpn.node.NodeConfigBundleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Node-facing endpoints called by remote agent instances (Spring profile {@code agent}). They are
 * served only on the internal mTLS connector ({@code opnl.internal.mtls-port}): the caller must
 * present a client certificate issued by the internal CA whose subject CN is {@code agent-<name>}
 * matching the requested node, and the source IP must match the pinned admin IP when one is set.
 * The shared {@code X-Internal-Token} is required as well (defense in depth).
 */
@Slf4j
@RestController
@RequestMapping("/internal/node")
@Tag(name = "Node agent", description = "Endpoints used by remote node agents (restricted network)")
public class InternalNodeController {

  private final NodeRegistryService nodeRegistryService;
  private final ClientCertReader clientCertReader;
  private final InternalProperties internalProperties;
  private final NodeConfigBundleService nodeConfigBundleService;

  public InternalNodeController(
      NodeRegistryService nodeRegistryService,
      ClientCertReader clientCertReader,
      InternalProperties internalProperties,
      NodeConfigBundleService nodeConfigBundleService) {
    this.nodeRegistryService = nodeRegistryService;
    this.clientCertReader = clientCertReader;
    this.internalProperties = internalProperties;
    this.nodeConfigBundleService = nodeConfigBundleService;
  }

  /** Registers (or re-registers) the calling agent's gateway node and returns its id. */
  @PostMapping("/register")
  public RegisterResult register(
      HttpServletRequest servletRequest, @RequestBody RegisterRequest request) {
    requireMtls(servletRequest);
    String cn = requireCertCn(servletRequest);
    requireCertForNode(cn, request.name());
    String nodeId =
        nodeRegistryService.upsertByAgent(
            request.name(),
            request.mgmtHost(),
            request.mgmtPortBase(),
            request.adminIp(),
            request.mgmtPassword(),
            servletRequest.getRemoteAddr());
    log.info("Node agent registered node '{}' ({})", request.name(), nodeId);
    return new RegisterResult(nodeId);
  }

  /** Refreshes the node's heartbeat so the central backend keeps reporting it online. */
  @PostMapping("/heartbeat")
  public void heartbeat(HttpServletRequest servletRequest, @RequestBody HeartbeatRequest request) {
    requireMtls(servletRequest);
    String cn = requireCertCn(servletRequest);
    OpenVpnNode node =
        nodeRegistryService
            .findNode(request.nodeId())
            .orElseThrow(() -> ApiException.notFound("node_not_found", "VPN node not found"));
    requireCertForNode(cn, node.getName());
    nodeRegistryService.heartbeat(request.nodeId(), servletRequest.getRemoteAddr());
  }

  /**
   * Returns the config bundle (daemon configs, PKI incl. the CRL, CCD, scripts, dnsmasq) for the
   * calling node. The caller must present a client certificate for the node and, when the node has
   * a pinned admin IP, connect from that address.
   */
  @PostMapping("/config")
  public NodeConfigBundleService.NodeConfigBundle config(
      HttpServletRequest servletRequest, @RequestBody ConfigRequest request) {
    requireMtls(servletRequest);
    String cn = requireCertCn(servletRequest);
    OpenVpnNode node =
        nodeRegistryService
            .findNode(request.nodeId())
            .orElseThrow(() -> ApiException.notFound("node_not_found", "VPN node not found"));
    requireCertForNode(cn, node.getName());
    nodeRegistryService.checkSourceIp(node.getId(), servletRequest.getRemoteAddr());
    return nodeConfigBundleService.bundleForNode(node.getId());
  }

  private void requireMtls(HttpServletRequest request) {
    if (request.getLocalPort() != internalProperties.mtlsPort()) {
      throw ApiException.forbidden(
          "mtls_required", "Node agent endpoints are only served over the internal mTLS connector");
    }
  }

  private String requireCertCn(HttpServletRequest request) {
    String cn = clientCertReader.subjectCn(request);
    if (cn == null || cn.isBlank()) {
      throw ApiException.forbidden(
          "client_cert_required", "A valid internal client certificate is required");
    }
    return cn;
  }

  private void requireCertForNode(String certCn, String nodeName) {
    String expected = "agent-" + (nodeName == null ? "" : nodeName.trim());
    if (!expected.equalsIgnoreCase(certCn.trim())) {
      throw ApiException.forbidden(
          "cert_identity_mismatch",
          "Client certificate '" + certCn + "' is not authorized for node '" + nodeName + "'");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RegisterRequest(
      String name, String mgmtHost, int mgmtPortBase, String adminIp, String mgmtPassword) {}

  public record HeartbeatRequest(String nodeId) {}

  public record ConfigRequest(String nodeId) {}

  public record RegisterResult(String nodeId) {}
}
