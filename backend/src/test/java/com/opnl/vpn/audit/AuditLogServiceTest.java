package com.opnl.vpn.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.api.admin.AuditLogDto;
import com.opnl.vpn.api.admin.PageDto;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.syslog.SyslogService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditLogServiceTest {

  private AuditLogRepository repository;
  private SettingsService settingsService;
  private UserRepository userRepository;
  private SyslogService syslogService;
  private AuditLogService service;

  @BeforeEach
  void setUp() {
    repository = mock(AuditLogRepository.class);
    settingsService = mock(SettingsService.class);
    userRepository = mock(UserRepository.class);
    syslogService = mock(SyslogService.class);
    service =
        new AuditLogService(
            repository, settingsService, userRepository, new ObjectMapper(), syslogService);
    when(settingsService.serverSettings()).thenReturn(Map.of());
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void recordResolvesCurrentActorFromSecurityContext() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("user1", "pwd"));
    when(userRepository.findById("user1"))
        .thenReturn(Optional.of(User.builder().id("user1").username("alice").build()));

    service.record("USER_CREATE", AuditLogService.CAT_USER, "u9", "user", Map.of("role", "ADMIN"));

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(repository).save(captor.capture());
    AuditLog saved = captor.getValue();
    assertThat(saved.getActorId()).isEqualTo("user1");
    assertThat(saved.getActorName()).isEqualTo("alice");
    assertThat(saved.getAction()).isEqualTo("USER_CREATE");
    assertThat(saved.getCategory()).isEqualTo(AuditLogService.CAT_USER);
    assertThat(saved.getTargetId()).isEqualTo("u9");
    assertThat(saved.getTargetType()).isEqualTo("user");
    assertThat(saved.getDetail()).contains("\"role\"");
    assertThat(saved.getCreatedAt()).isNotNull();
    verify(syslogService)
        .emit(
            "local0",
            "category="
                + saved.getCategory()
                + " action="
                + saved.getAction()
                + " actor=alice actorId=user1 target=user/u9 ip=- detail="
                + saved.getDetail());
  }

  @Test
  void recordWithUnknownActorKeepsNullActor() {
    when(userRepository.findById("ghost")).thenReturn(Optional.empty());
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("ghost", "pwd"));

    service.record("AUTH_FAIL", AuditLogService.CAT_AUTH, null, null, null);

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getActorId()).isNull();
    assertThat(captor.getValue().getActorName()).isNull();
    verify(syslogService).emit(eq("auth"), any(String.class));
  }

  @Test
  void recordWithoutAuthenticationStillPersistsEvent() {
    service.record("PORTAL_LOGIN", AuditLogService.CAT_PORTAL, null, null, Map.of());

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getDetail()).isNull();
  }

  @Test
  void recordCapturesRequestIpWhenAttributesPresent() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.5");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    service.record("U1", "U2", "ACTION", AuditLogService.CAT_SYSTEM, null, null, null, "1.2.3.4");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getIp()).isEqualTo("1.2.3.4");
  }

  @Test
  void recordIsBestEffortWhenPersistenceFails() {
    when(repository.save(any(AuditLog.class))).thenThrow(new IllegalStateException("db down"));

    service.record("U1", "U2", "ACTION", AuditLogService.CAT_SYSTEM, null, null, null, null);

    verify(syslogService, org.mockito.Mockito.never()).emit(any(String.class), any(String.class));
  }

  @Test
  void recordWithExplicitActorSkipsActorResolution() {
    service.record(
        "alice", "Alice", "CERT_REVOKE", AuditLogService.CAT_CERT, "c1", "cert", null, "10.0.0.9");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getActorName()).isEqualTo("Alice");
    assertThat(captor.getValue().getDetail()).isNull();
    assertThat(captor.getValue().getIp()).isEqualTo("10.0.0.9");
    verify(syslogService)
        .emit(eq("local0"), org.mockito.ArgumentMatchers.contains("target=cert/c1"));
  }

  @Test
  void searchClampsPagingAndAppliesWildcards() {
    AuditLog log =
        AuditLog.builder()
            .id("a1")
            .action("USER_CREATE")
            .category(AuditLogService.CAT_USER)
            .createdAt(Instant.now())
            .build();
    when(repository.search(any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 100), 1));

    PageDto<AuditLogDto> result =
        service.search(
            -3,
            500,
            " user_create ",
            " alice ",
            Instant.now().minus(1, ChronoUnit.DAYS),
            Instant.now());

    ArgumentCaptor<PageRequest> pageRequest = ArgumentCaptor.forClass(PageRequest.class);
    verify(repository)
        .search(
            eq("%user_create%"),
            eq("alice"),
            eq("%alice%"),
            any(Instant.class),
            any(Instant.class),
            pageRequest.capture());
    assertThat(pageRequest.getValue().getPageNumber()).isZero();
    assertThat(pageRequest.getValue().getPageSize()).isEqualTo(100);
    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(100);
    assertThat(result.totalElements()).isEqualTo(1);
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).action()).isEqualTo("USER_CREATE");
  }

  @Test
  void searchPassesThroughUnfilteredWildcards() {
    when(repository.search(any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

    PageDto<AuditLogDto> result = service.search(0, 25, null, null, null, null);

    assertThat(result.content()).isEmpty();
    verify(repository)
        .search(eq(null), eq(null), eq(null), eq(null), eq(null), any(PageRequest.class));
  }

  @Test
  void purgeOldUsesConfiguredRetentionWindow() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of(SettingKeys.AUDIT_LOGS_RETENTION_DAYS, 30));
    when(repository.deleteOlderThan(any(Instant.class))).thenReturn(5);

    service.purgeOld();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(repository).deleteOlderThan(cutoff.capture());
    assertThat(Instant.now().minus(30, ChronoUnit.DAYS)).isAfterOrEqualTo(cutoff.getValue());
  }

  @Test
  void purgeOldDefaultsToNinetyDaysWhenUnset() {
    when(repository.deleteOlderThan(any(Instant.class))).thenReturn(0);

    service.purgeOld();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(repository).deleteOlderThan(cutoff.capture());
    assertThat(Instant.now().minus(90, ChronoUnit.DAYS)).isAfterOrEqualTo(cutoff.getValue());
  }

  @Test
  void purgeOldIgnoresOutOfRangeRetentionValues() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of(SettingKeys.AUDIT_LOGS_RETENTION_DAYS, 99999));
    when(repository.deleteOlderThan(any(Instant.class))).thenReturn(0);

    service.purgeOld();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(repository).deleteOlderThan(cutoff.capture());
    assertThat(Instant.now().minus(90, ChronoUnit.DAYS)).isAfterOrEqualTo(cutoff.getValue());
  }

  @Test
  void purgeOldSwallowsFailures() {
    doThrow(new IllegalStateException("locked"))
        .when(repository)
        .deleteOlderThan(any(Instant.class));

    service.purgeOld();
  }
}
