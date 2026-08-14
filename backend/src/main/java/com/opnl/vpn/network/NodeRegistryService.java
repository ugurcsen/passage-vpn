package com.opnl.vpn.network;

import com.opnl.vpn.api.admin.OpenVpnNodeDto;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD for registered VPN gateway nodes (openvpn_nodes). The local deployment is implicit; remote
 * nodes are registered here so the central backend can route status/kill/monitor requests and
 * accept agent heartbeats. Every mutation is recorded in the audit log.
 */
@Slf4j
@Service
public class NodeRegistryService {

  private final OpenVpnNodeRepository nodeRepository;
  private final AuditLogService auditLogService;

  public NodeRegistryService(OpenVpnNodeRepository nodeRepository, AuditLogService auditLogService) {
    this.nodeRepository = nodeRepository;
    this.auditLogService = auditLogService;
  }

  @Transactional(readOnly = true)
  public List<OpenVpnNodeDto> list() {
    Instant now = Instant.now();
    return nodeRepository.findAllByOrderByCreatedAtAsc().stream()
        .map(node -> OpenVpnNodeDto.from(node, now))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<OpenVpnNode> enabledNodes() {
    return nodeRepository.findByEnabledTrueOrderByCreatedAtAsc();
  }

  @Transactional(readOnly = true)
  public OpenVpnNode requireNode(String id) {
    return nodeRepository
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("node_not_found", "VPN node not found"));
  }

  /** Non-throwing lookup used by routing components (e.g. management endpoint resolution). */
  @Transactional(readOnly = true)
  public Optional<OpenVpnNode> findNode(String id) {
    return nodeRepository.findById(id);
  }

  @Transactional
  public OpenVpnNodeDto create(NodeRequest request) {
    validate(request, null);
    OpenVpnNode node =
        OpenVpnNode.builder()
            .id(UUID.randomUUID().toString())
            .name(request.name().trim().toLowerCase(Locale.ROOT))
            .mgmtHost(request.mgmtHost().trim())
            .mgmtPortBase(request.mgmtPortBase())
            .adminIp(blankToNull(request.adminIp()))
            .enabled(request.enabled() == null || request.enabled())
            .createdAt(Instant.now())
            .build();
    OpenVpnNode saved = nodeRepository.save(node);
    auditLogService.record(
        "NODE_CREATE",
        AuditLogService.CAT_NODE,
        saved.getId(),
        "vpn_node",
        Map.of(
            "name",
            saved.getName(),
            "mgmtHost",
            saved.getMgmtHost(),
            "mgmtPortBase",
            saved.getMgmtPortBase()));
    return OpenVpnNodeDto.from(saved, Instant.now());
  }

  @Transactional
  public OpenVpnNodeDto update(String id, NodeRequest request) {
    OpenVpnNode node = requireNode(id);
    validate(request, id);
    node.setName(request.name().trim().toLowerCase(Locale.ROOT));
    node.setMgmtHost(request.mgmtHost().trim());
    node.setMgmtPortBase(request.mgmtPortBase());
    node.setAdminIp(blankToNull(request.adminIp()));
    if (request.enabled() != null) {
      node.setEnabled(request.enabled());
    }
    auditLogService.record("NODE_UPDATE", AuditLogService.CAT_NODE, id, "vpn_node", null);
    return OpenVpnNodeDto.from(nodeRepository.save(node), Instant.now());
  }

  @Transactional
  public void delete(String id) {
    requireNode(id);
    nodeRepository.deleteById(id);
    auditLogService.record("NODE_DELETE", AuditLogService.CAT_NODE, id, "vpn_node", null);
  }

  @Transactional
  public OpenVpnNodeDto setEnabled(String id, boolean enabled) {
    OpenVpnNode node = requireNode(id);
    node.setEnabled(enabled);
    auditLogService.record(
        enabled ? "NODE_ENABLE" : "NODE_DISABLE",
        AuditLogService.CAT_NODE,
        id,
        "vpn_node",
        null);
    return OpenVpnNodeDto.from(nodeRepository.save(node), Instant.now());
  }

  /** Records a fresh agent heartbeat for a node. */
  @Transactional
  public void heartbeat(String id) {
    OpenVpnNode node = requireNode(id);
    node.setLastSeenAt(Instant.now());
    nodeRepository.save(node);
  }

  private void validate(NodeRequest request, String currentId) {
    String name = request.name() == null ? "" : request.name().trim().toLowerCase(Locale.ROOT);
    if (name.isBlank()) {
      throw ApiException.badRequest("missing_name", "Node name is required");
    }
    if (name.length() > 64) {
      throw ApiException.badRequest("name_too_long", "Node name must be 64 characters or fewer");
    }
    if (request.mgmtHost() == null || request.mgmtHost().isBlank()) {
      throw ApiException.badRequest("missing_mgmt_host", "Management host is required");
    }
    if (request.mgmtPortBase() < 1 || request.mgmtPortBase() > 65535) {
      throw ApiException.badRequest("invalid_mgmt_port", "Management port base must be 1-65535");
    }
    nodeRepository
        .findByNameIgnoreCase(name)
        .ifPresent(
            existing -> {
              if (!existing.getId().equals(currentId)) {
                throw ApiException.conflict(
                    "node_name_exists", "A node named " + name + " already exists");
              }
            });
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Payload for creating or updating a VPN node. */
  public record NodeRequest(
      String name, String mgmtHost, int mgmtPortBase, String adminIp, Boolean enabled) {}
}
