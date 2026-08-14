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

  public NodeRegistryService(
      OpenVpnNodeRepository nodeRepository, AuditLogService auditLogService) {
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
    if (request.mgmtPassword() == null || request.mgmtPassword().isBlank()) {
      throw ApiException.badRequest(
          "missing_mgmt_password", "Management password is required for the node");
    }
    OpenVpnNode node =
        OpenVpnNode.builder()
            .id(UUID.randomUUID().toString())
            .name(request.name().trim().toLowerCase(Locale.ROOT))
            .mgmtHost(request.mgmtHost().trim())
            .mgmtPortBase(request.mgmtPortBase())
            .adminIp(blankToNull(request.adminIp()))
            .mgmtPassword(request.mgmtPassword())
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
    if (request.mgmtPassword() != null && !request.mgmtPassword().isBlank()) {
      node.setMgmtPassword(request.mgmtPassword());
    }
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
        enabled ? "NODE_ENABLE" : "NODE_DISABLE", AuditLogService.CAT_NODE, id, "vpn_node", null);
    return OpenVpnNodeDto.from(nodeRepository.save(node), Instant.now());
  }

  /**
   * Records a fresh agent heartbeat for a node. When the node has a pinned admin IP, the heartbeat
   * must originate from that exact source IP.
   */
  @Transactional
  public void heartbeat(String id, String sourceIp) {
    OpenVpnNode node = requireNode(id);
    enforceSourceIp(node, sourceIp);
    node.setLastSeenAt(Instant.now());
    node.setLastSeenIp(sourceIp);
    nodeRepository.save(node);
  }

  /**
   * Idempotent upsert used by remote node agents (profile {@code agent}). Nodes are keyed by name:
   * the first call creates the node and any later call refreshes its endpoint details, re-enables
   * it and marks it online. Returns the node id so the agent can heartbeat afterwards.
   *
   * <p>The agent presents the management password of its own gateway (persisted here so the central
   * can open management sessions) plus the source IP it connected from, which is pinned when the
   * node has an admin IP set.
   */
  @Transactional
  public String upsertByAgent(
      String name,
      String mgmtHost,
      int mgmtPortBase,
      String adminIp,
      String mgmtPassword,
      String sourceIp) {
    String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank() || normalized.length() > 64) {
      throw ApiException.badRequest("missing_name", "Node name is required (max 64 chars)");
    }
    if (mgmtHost == null || mgmtHost.isBlank()) {
      throw ApiException.badRequest("missing_mgmt_host", "Management host is required");
    }
    if (mgmtPortBase < 1 || mgmtPortBase > 65535) {
      throw ApiException.badRequest("invalid_mgmt_port", "Management port base must be 1-65535");
    }
    if (mgmtPassword == null || mgmtPassword.isBlank()) {
      throw ApiException.badRequest(
          "missing_mgmt_password", "Management password is required for agent registration");
    }
    OpenVpnNode existing = nodeRepository.findByNameIgnoreCase(normalized).orElse(null);
    if (existing == null) {
      enforceSourceIpAgainst(normalized, adminIp, sourceIp);
      OpenVpnNode node =
          OpenVpnNode.builder()
              .id(UUID.randomUUID().toString())
              .name(normalized)
              .mgmtHost(mgmtHost.trim())
              .mgmtPortBase(mgmtPortBase)
              .adminIp(blankToNull(adminIp))
              .mgmtPassword(mgmtPassword)
              .lastSeenIp(sourceIp)
              .enabled(true)
              .createdAt(Instant.now())
              .lastSeenAt(Instant.now())
              .build();
      OpenVpnNode saved = nodeRepository.save(node);
      auditLogService.record(
          "NODE_CREATE",
          AuditLogService.CAT_NODE,
          saved.getId(),
          "vpn_node",
          Map.of("name", saved.getName(), "mgmtHost", saved.getMgmtHost()));
      log.info("Agent registered new node '{}' ({})", saved.getName(), saved.getId());
      return saved.getId();
    }
    enforceSourceIp(existing, sourceIp);
    existing.setMgmtHost(mgmtHost.trim());
    existing.setMgmtPortBase(mgmtPortBase);
    existing.setAdminIp(blankToNull(adminIp));
    existing.setMgmtPassword(mgmtPassword);
    existing.setLastSeenIp(sourceIp);
    existing.setEnabled(true);
    existing.setLastSeenAt(Instant.now());
    nodeRepository.save(existing);
    auditLogService.record(
        "NODE_UPDATE",
        AuditLogService.CAT_NODE,
        existing.getId(),
        "vpn_node",
        Map.of("name", existing.getName(), "source", "agent"));
    log.info("Agent re-registered node '{}' ({})", existing.getName(), existing.getId());
    return existing.getId();
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

  /**
   * Rejects a request when the node has a pinned admin IP that does not match the observed source
   * IP. Throws 403 otherwise; a missing admin IP means "allow any" (defense in depth only — the
   * mTLS certificate is the primary identity).
   */
  private void enforceSourceIp(OpenVpnNode node, String sourceIp) {
    enforceSourceIpAgainst(node.getName(), node.getAdminIp(), sourceIp);
  }

  private void enforceSourceIpAgainst(String nodeName, String adminIp, String sourceIp) {
    String pinned = blankToNull(adminIp);
    if (pinned == null || sourceIp == null || sourceIp.isBlank()) {
      return;
    }
    if (!pinned.equals(sourceIp)) {
      throw ApiException.forbidden(
          "source_ip_mismatch",
          "Registration for node '"
              + nodeName
              + "' rejected: source IP "
              + sourceIp
              + " does not match pinned admin IP "
              + pinned);
    }
  }

  /** Payload for creating or updating a VPN node. */
  public record NodeRequest(
      String name,
      String mgmtHost,
      int mgmtPortBase,
      String adminIp,
      String mgmtPassword,
      Boolean enabled) {}
}
