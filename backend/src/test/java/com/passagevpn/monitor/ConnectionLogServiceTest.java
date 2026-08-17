package com.passagevpn.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.api.admin.ConnectionLogDto;
import com.passagevpn.group.GroupScope;
import com.passagevpn.monitor.MgmtStatus.MgmtClientStatus;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class ConnectionLogServiceTest {

  private ConnectionLogRepository repository;
  private TrafficAggregator aggregator;
  private SettingsService settingsService;
  private GroupScope groupScope;
  private ConnectionLogService service;

  @BeforeEach
  void setUp() {
    repository = mock(ConnectionLogRepository.class);
    aggregator = new TrafficAggregator();
    settingsService = mock(SettingsService.class);
    groupScope = mock(GroupScope.class);
    service = new ConnectionLogService(repository, aggregator, settingsService, groupScope);
  }

  private User admin() {
    return User.builder()
        .id("admin1")
        .username("admin")
        .role(User.Role.ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  @Test
  void sessionStartedPersistsOpenRow() {
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.sessionStarted("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");

    verify(repository).save(any(ConnectionLog.class));
  }

  @Test
  void sessionStartedIgnoresBlankCommonName() {
    service.sessionStarted("alice", "", null, null, null);
    verify(repository, never()).save(any());
  }

  @Test
  void sessionEndedFinalizesOpenRowWithAggregatorBytes() {
    ConnectionLog open =
        ConnectionLog.builder()
            .id("log1")
            .username("alice")
            .commonName("alice")
            .connectedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
    when(repository.findFirstByCommonNameAndDisconnectedAtIsNullOrderByConnectedAtDesc("alice"))
        .thenReturn(Optional.of(open));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Instant t0 = Instant.parse("2026-01-01T00:05:00Z");
    aggregator.update(
        List.of(new MgmtClientStatus("alice", "203.0.113.5", "10.8.0.2", null, 4096, 8192, t0, 1)),
        t0);

    service.sessionEnded("alice");

    assertThat(open.getDisconnectedAt()).isNotNull();
    assertThat(open.getBytesIn()).isEqualTo(4096);
    assertThat(open.getBytesOut()).isEqualTo(8192);
    verify(repository).save(open);
  }

  @Test
  void sessionEndedWithoutOpenRowIsNoop() {
    when(repository.findFirstByCommonNameAndDisconnectedAtIsNullOrderByConnectedAtDesc("alice"))
        .thenReturn(Optional.empty());

    service.sessionEnded("alice");

    verify(repository, never()).save(any());
  }

  @Test
  void recentMapsEntitiesToDtos() {
    ConnectionLog log =
        ConnectionLog.builder()
            .id("log1")
            .username("alice")
            .commonName("alice")
            .virtualIp("10.8.0.2")
            .connectedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .disconnectedAt(Instant.parse("2026-01-01T00:02:00Z"))
            .bytesIn(100)
            .bytesOut(200)
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
    when(groupScope.scopedUsernames(any())).thenReturn(null);
    when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(log)));

    List<ConnectionLogDto> recent = service.recent(admin(), 25);

    assertThat(recent).hasSize(1);
    ConnectionLogDto dto = recent.get(0);
    assertThat(dto.username()).isEqualTo("alice");
    assertThat(dto.bytesIn()).isEqualTo(100);
    assertThat(dto.durationSeconds()).isEqualTo(120);
  }

  @Test
  void recentScopesToManagedUsernamesForGroupAdmin() {
    ConnectionLog log =
        ConnectionLog.builder()
            .id("log1")
            .username("alice")
            .commonName("alice")
            .connectedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
    User groupAdmin =
        User.builder()
            .id("gadmin1")
            .username("gadmin")
            .role(User.Role.GROUP_ADMIN)
            .createdAt(Instant.now())
            .build();
    when(groupScope.scopedUsernames(groupAdmin)).thenReturn(Set.of("alice"));
    when(repository.findByUsernameInOrderByConnectedAtDesc(any(), any(Pageable.class)))
        .thenReturn(List.of(log));

    List<ConnectionLogDto> recent = service.recent(groupAdmin, 25);

    assertThat(recent).hasSize(1);
    verify(repository)
        .findByUsernameInOrderByConnectedAtDesc(
            org.mockito.ArgumentMatchers.eq(Set.of("alice")), any(Pageable.class));
  }

  @Test
  void purgeOldDeletesBeforeCutoff() {
    when(repository.deleteClosedBefore(any())).thenReturn(3);
    service.purgeOld();
    verify(repository).deleteClosedBefore(any(Instant.class));
  }

  @Test
  void reconcileClosesOpenRowsMissingFromLiveView() {
    ConnectionLog open =
        ConnectionLog.builder()
            .id("log1")
            .username("alice")
            .commonName("alice")
            .connectedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
    when(repository.findAllByDisconnectedAtIsNull()).thenReturn(List.of(open));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.reconcileOpenSessions(Set.of("bob", "carol"));

    assertThat(open.getDisconnectedAt()).isNotNull();
    verify(repository).save(open);
  }

  @Test
  void reconcileKeepsRowsPresentInLiveView() {
    ConnectionLog live =
        ConnectionLog.builder()
            .id("log1")
            .username("alice")
            .commonName("alice")
            .connectedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
    when(repository.findAllByDisconnectedAtIsNull()).thenReturn(List.of(live));

    service.reconcileOpenSessions(Set.of("alice"));

    assertThat(live.getDisconnectedAt()).isNull();
    verify(repository, never()).save(any());
  }

  @Test
  void reconcileWithEmptyLiveViewClosesEverything() {
    // Empty live view is valid (no clients connected): all open rows are stale.
    ConnectionLog open =
        ConnectionLog.builder()
            .id("log1")
            .username("alice")
            .commonName("alice")
            .connectedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
    when(repository.findAllByDisconnectedAtIsNull()).thenReturn(List.of(open));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.reconcileOpenSessions(Set.of());

    assertThat(open.getDisconnectedAt()).isNotNull();
  }

  @Test
  void reconcileAttachesLastKnownBytesFromAggregator() {
    ConnectionLog open =
        ConnectionLog.builder()
            .id("log1")
            .username("alice")
            .commonName("alice")
            .connectedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
    when(repository.findAllByDisconnectedAtIsNull()).thenReturn(List.of(open));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    Instant t0 = Instant.parse("2026-01-01T00:05:00Z");
    aggregator.update(
        List.of(new MgmtClientStatus("alice", "203.0.113.5", "10.8.0.2", null, 4096, 8192, t0, 1)),
        t0);

    service.reconcileOpenSessions(Set.of("bob"));

    assertThat(open.getBytesIn()).isEqualTo(4096);
    assertThat(open.getBytesOut()).isEqualTo(8192);
  }

  @Test
  void reconcileWithNullLiveViewIsNoop() {
    service.reconcileOpenSessions(null);
    verify(repository, never()).findAllByDisconnectedAtIsNull();
    verify(repository, never()).save(any());
  }
}
