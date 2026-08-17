package com.passagevpn.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.audit.AuditLogService;
import com.passagevpn.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NodeRegistryServiceTest {

  private OpenVpnNodeRepository nodeRepository;
  private AuditLogService auditLogService;
  private NodeRegistryService service;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(OpenVpnNodeRepository.class);
    auditLogService = mock(AuditLogService.class);
    service = new NodeRegistryService(nodeRepository, auditLogService);
    when(nodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private NodeRegistryService.NodeRequest request(String name, String host, int port) {
    return new NodeRegistryService.NodeRequest(
        name, host, port, "10.0.0.5", null, "mgmt-pass", true);
  }

  private OpenVpnNode node(String id, String name, String host, int port) {
    return OpenVpnNode.builder()
        .id(id)
        .name(name)
        .mgmtHost(host)
        .mgmtPortBase(port)
        .enabled(true)
        .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
        .build();
  }

  @Test
  void createNormalizesNameAndAudits() {
    when(nodeRepository.findByNameIgnoreCase("edge-eu")).thenReturn(Optional.empty());

    var result = service.create(request("  Edge-EU ", "vpn-eu.example.com", 7505));

    ArgumentCaptor<OpenVpnNode> captor = ArgumentCaptor.forClass(OpenVpnNode.class);
    verify(nodeRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("edge-eu");
    assertThat(captor.getValue().getMgmtHost()).isEqualTo("vpn-eu.example.com");
    assertThat(captor.getValue().getMgmtPortBase()).isEqualTo(7505);
    assertThat(captor.getValue().isEnabled()).isTrue();
    assertThat(result.name()).isEqualTo("edge-eu");
    verify(auditLogService)
        .record(
            eq("NODE_CREATE"), eq(AuditLogService.CAT_NODE), anyString(), eq("vpn_node"), any());
  }

  @Test
  void createRejectsDuplicateName() {
    when(nodeRepository.findByNameIgnoreCase("edge-eu"))
        .thenReturn(Optional.of(node("n1", "edge-eu", "vpn-eu.example.com", 7505)));

    assertThatThrownBy(() -> service.create(request("edge-eu", "other.example.com", 7505)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("already exists");
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void createRejectsBlankName() {
    assertThatThrownBy(() -> service.create(request("  ", "vpn-eu.example.com", 7505)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void createRejectsBlankMgmtHost() {
    assertThatThrownBy(() -> service.create(request("edge-eu", " ", 7505)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void createRejectsInvalidPort() {
    assertThatThrownBy(() -> service.create(request("edge-eu", "vpn-eu.example.com", 70000)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.create(request("edge-eu", "vpn-eu.example.com", 0)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void updateChangesFieldsAndAudits() {
    when(nodeRepository.findById("n1"))
        .thenReturn(Optional.of(node("n1", "edge-eu", "old.example.com", 7505)));
    when(nodeRepository.findByNameIgnoreCase("edge-us")).thenReturn(Optional.empty());

    var result =
        service.update(
            "n1",
            new NodeRegistryService.NodeRequest(
                "edge-us",
                "vpn-us.example.com",
                7506,
                null,
                "vpn-us-public.example.com",
                "mgmt-pass",
                true));

    assertThat(result.name()).isEqualTo("edge-us");
    assertThat(result.mgmtHost()).isEqualTo("vpn-us.example.com");
    assertThat(result.mgmtPortBase()).isEqualTo(7506);
    assertThat(result.adminIp()).isNull();
    assertThat(result.adminHost()).isEqualTo("vpn-us-public.example.com");
    assertThat(result.mgmtPasswordSet()).isTrue();
    verify(auditLogService).record("NODE_UPDATE", AuditLogService.CAT_NODE, "n1", "vpn_node", null);
  }

  @Test
  void updateKeepsExistingPasswordWhenOmitted() {
    OpenVpnNode existing = node("n1", "edge-eu", "vpn-eu.example.com", 7505);
    existing.setMgmtPassword("old-pass");
    when(nodeRepository.findById("n1")).thenReturn(Optional.of(existing));
    when(nodeRepository.findByNameIgnoreCase("edge-eu")).thenReturn(Optional.of(existing));

    service.update(
        "n1",
        new NodeRegistryService.NodeRequest(
            "edge-eu", "vpn-eu.example.com", 7505, null, null, null, true));

    assertThat(existing.getMgmtPassword()).isEqualTo("old-pass");
  }

  @Test
  void createRejectsMissingMgmtPassword() {
    assertThatThrownBy(
            () ->
                service.create(
                    new NodeRegistryService.NodeRequest(
                        "edge-eu", "vpn-eu.example.com", 7505, null, null, null, true)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Management password");
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void updateRejectsRenamingToExistingName() {
    when(nodeRepository.findById("n1"))
        .thenReturn(Optional.of(node("n1", "edge-eu", "old.example.com", 7505)));
    when(nodeRepository.findByNameIgnoreCase("edge-us"))
        .thenReturn(Optional.of(node("n2", "edge-us", "other.example.com", 7505)));

    assertThatThrownBy(
            () ->
                service.update(
                    "n1",
                    new NodeRegistryService.NodeRequest(
                        "edge-us", "vpn-us.example.com", 7506, null, null, "mgmt-pass", true)))
        .isInstanceOf(ApiException.class);
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void deleteRemovesNodeAndAudits() {
    when(nodeRepository.findById("n1"))
        .thenReturn(Optional.of(node("n1", "edge-eu", "vpn-eu.example.com", 7505)));

    service.delete("n1");

    verify(nodeRepository).deleteById("n1");
    verify(auditLogService).record("NODE_DELETE", AuditLogService.CAT_NODE, "n1", "vpn_node", null);
  }

  @Test
  void deleteUnknownNodeThrows() {
    when(nodeRepository.findById("n1")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete("n1")).isInstanceOf(ApiException.class);
  }

  @Test
  void setEnabledFlipsFlagAndAudits() {
    when(nodeRepository.findById("n1"))
        .thenReturn(Optional.of(node("n1", "edge-eu", "vpn-eu.example.com", 7505)));

    var result = service.setEnabled("n1", false);

    assertThat(result.enabled()).isFalse();
    verify(auditLogService)
        .record("NODE_DISABLE", AuditLogService.CAT_NODE, "n1", "vpn_node", null);
  }

  @Test
  void heartbeatTouchesLastSeen() {
    OpenVpnNode existing = node("n1", "edge-eu", "vpn-eu.example.com", 7505);
    existing.setLastSeenAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(nodeRepository.findById("n1")).thenReturn(Optional.of(existing));

    service.heartbeat("n1", "10.0.0.5");

    assertThat(existing.getLastSeenAt()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(existing.getLastSeenIp()).isEqualTo("10.0.0.5");
    verify(nodeRepository).save(existing);
  }

  @Test
  void heartbeatRejectsMismatchedSourceIp() {
    OpenVpnNode existing = node("n1", "edge-eu", "vpn-eu.example.com", 7505);
    existing.setAdminIp("10.0.0.5");
    when(nodeRepository.findById("n1")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.heartbeat("n1", "203.0.113.9"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("does not match pinned admin IP");
  }

  @Test
  void heartbeatAcceptsAnySourceWithoutAdminIp() {
    OpenVpnNode existing = node("n1", "edge-eu", "vpn-eu.example.com", 7505);
    when(nodeRepository.findById("n1")).thenReturn(Optional.of(existing));

    service.heartbeat("n1", "203.0.113.9");

    assertThat(existing.getLastSeenIp()).isEqualTo("203.0.113.9");
  }

  @Test
  void listMarksOnlineByFreshHeartbeat() {
    Instant now = Instant.now();
    OpenVpnNode fresh = node("n1", "edge-eu", "vpn-eu.example.com", 7505);
    fresh.setLastSeenAt(now.minusSeconds(5));
    OpenVpnNode stale = node("n2", "edge-us", "vpn-us.example.com", 7505);
    stale.setLastSeenAt(now.minusSeconds(300));
    OpenVpnNode never = node("n3", "edge-ap", "vpn-ap.example.com", 7505);
    never.setLastSeenAt(null);
    when(nodeRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(fresh, stale, never));

    List<com.passagevpn.api.admin.OpenVpnNodeDto> result = service.list();

    assertThat(result).hasSize(3);
    assertThat(result.get(0).online()).isTrue();
    assertThat(result.get(1).online()).isFalse();
    assertThat(result.get(2).online()).isFalse();
  }

  @Test
  void enabledNodesReturnsOnlyEnabled() {
    when(nodeRepository.findByEnabledTrueOrderByCreatedAtAsc())
        .thenReturn(List.of(node("n1", "edge-eu", "vpn-eu.example.com", 7505)));
    assertThat(service.enabledNodes()).hasSize(1);
  }

  @Test
  void upsertByAgentCreatesWhenMissing() {
    when(nodeRepository.findByNameIgnoreCase("edge-eu")).thenReturn(Optional.empty());

    String id =
        service.upsertByAgent(
            "Edge-EU", "vpn-eu.example.com", 7505, "10.0.0.5", "mgmt-pass", "10.0.0.5");

    ArgumentCaptor<OpenVpnNode> captor = ArgumentCaptor.forClass(OpenVpnNode.class);
    verify(nodeRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("edge-eu");
    assertThat(captor.getValue().isEnabled()).isTrue();
    assertThat(captor.getValue().getLastSeenAt()).isNotNull();
    assertThat(captor.getValue().getMgmtPassword()).isEqualTo("mgmt-pass");
    assertThat(captor.getValue().getLastSeenIp()).isEqualTo("10.0.0.5");
    assertThat(id).isEqualTo(captor.getValue().getId());
    verify(auditLogService).record(anyString(), any(), eq(id), any(), any());
  }

  @Test
  void upsertByAgentRejectsSourceIpMismatch() {
    assertThatThrownBy(
            () ->
                service.upsertByAgent(
                    "edge-eu", "vpn-eu.example.com", 7505, "10.0.0.5", "mgmt-pass", "203.0.113.9"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("does not match pinned admin IP");
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void upsertByAgentRejectsMissingMgmtPassword() {
    assertThatThrownBy(
            () ->
                service.upsertByAgent(
                    "edge-eu", "vpn-eu.example.com", 7505, null, null, "10.0.0.5"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Management password");
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void upsertByAgentReusesExistingNode() {
    OpenVpnNode existing = node("n1", "edge-eu", "vpn-eu.example.com", 7505);
    existing.setEnabled(false);
    when(nodeRepository.findByNameIgnoreCase("edge-eu")).thenReturn(Optional.of(existing));

    String id =
        service.upsertByAgent("edge-eu", "10.0.0.9", 7605, null, "mgmt-pass", "203.0.113.9");

    assertThat(id).isEqualTo("n1");
    assertThat(existing.getMgmtHost()).isEqualTo("10.0.0.9");
    assertThat(existing.getMgmtPortBase()).isEqualTo(7605);
    assertThat(existing.getAdminIp()).isNull();
    assertThat(existing.getMgmtPassword()).isEqualTo("mgmt-pass");
    assertThat(existing.isEnabled()).isTrue();
    assertThat(existing.getLastSeenAt()).isNotNull();
    verify(nodeRepository).save(existing);
  }

  @Test
  void upsertByAgentRejectsBlankName() {
    assertThatThrownBy(
            () -> service.upsertByAgent(" ", "host", 7505, null, "mgmt-pass", "10.0.0.5"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Node name is required");
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void upsertByAgentRejectsInvalidPort() {
    assertThatThrownBy(
            () -> service.upsertByAgent("edge-eu", "host", 70000, null, "mgmt-pass", "10.0.0.5"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Management port base");
  }
}
