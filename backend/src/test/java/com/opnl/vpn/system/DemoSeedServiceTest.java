package com.opnl.vpn.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.access.AccessRuleDto;
import com.opnl.vpn.access.AccessRuleRepository;
import com.opnl.vpn.access.AccessRuleService;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.ccd.CcdService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.dns.DnsOverrideService;
import com.opnl.vpn.dns.DnsRecordDto;
import com.opnl.vpn.dns.DnsRecordRepository;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.monitor.ConnectionLogRepository;
import com.opnl.vpn.pki.CertificateRepository;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.setup.SetupService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for the demo dataset seeding logic. */
class DemoSeedServiceTest {

  private UserRepository userRepository;
  private GroupRepository groupRepository;
  private GroupMemberRepository memberRepository;
  private PasswordEncoder passwordEncoder;
  private SettingsService settingsService;
  private CcdService ccdService;
  private AccessRuleService accessRuleService;
  private AccessRuleRepository ruleRepository;
  private DnsOverrideService dnsOverrideService;
  private DnsRecordRepository recordRepository;
  private CertificateRepository certificateRepository;
  private ConnectionLogRepository connectionLogRepository;
  private SetupService setupService;
  private AuditLogService auditLogService;
  private DemoSeedService service;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    groupRepository = mock(GroupRepository.class);
    memberRepository = mock(GroupMemberRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    settingsService = mock(SettingsService.class);
    ccdService = mock(CcdService.class);
    accessRuleService = mock(AccessRuleService.class);
    ruleRepository = mock(AccessRuleRepository.class);
    dnsOverrideService = mock(DnsOverrideService.class);
    recordRepository = mock(DnsRecordRepository.class);
    certificateRepository = mock(CertificateRepository.class);
    connectionLogRepository = mock(ConnectionLogRepository.class);
    setupService = mock(SetupService.class);
    auditLogService = mock(AuditLogService.class);
    service =
        new DemoSeedService(
            userRepository,
            groupRepository,
            memberRepository,
            passwordEncoder,
            settingsService,
            ccdService,
            accessRuleService,
            ruleRepository,
            dnsOverrideService,
            recordRepository,
            certificateRepository,
            connectionLogRepository,
            setupService,
            auditLogService);
    when(setupService.complete()).thenReturn(true);
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(passwordEncoder.encode(any())).thenAnswer(inv -> "hashed:" + inv.getArgument(0));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void seedsFourUsersTwoGroupsAndMarksSeeded() {
    int users = service.seed(false);

    assertThat(users).isEqualTo(4);
    verify(userRepository, times(4)).save(any(User.class));
    verify(groupRepository, times(2)).save(any(Group.class));
    verify(memberRepository, times(3)).save(any());
    verify(settingsService, times(2)).setGroupSetting(any(), eq(SettingKeys.STATIC_IP_POOL), any());
    verify(settingsService).setServerSetting(DemoSeedService.DEMO_SEEDED_KEY, true);
    verify(ccdService).setStaticIp(any(), eq("10.8.0.100"));
    verify(ccdService).setStaticIp(any(), eq("10.8.0.150"));
    verify(accessRuleService, times(4)).create(any(AccessRuleDto.class));
    verify(dnsOverrideService, times(2)).create(any(DnsRecordDto.class));
    verify(certificateRepository, times(3)).save(any());
    verify(connectionLogRepository, times(2)).save(any());
    verify(auditLogService)
        .record(eq("DEMO_SEED"), eq(AuditLogService.CAT_SYSTEM), isNull(), eq("system"), anyMap());
  }

  @Test
  void seededReflectsMarkerSetting() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of(DemoSeedService.DEMO_SEEDED_KEY, true));
    assertThat(service.seeded()).isTrue();

    when(settingsService.serverSettings()).thenReturn(Map.of());
    assertThat(service.seeded()).isFalse();
  }

  @Test
  void refusesWhenSetupIncomplete() {
    when(setupService.complete()).thenReturn(false);
    assertThatThrownBy(() -> service.seed(false))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              ApiException ex = (ApiException) e;
              assertThat(ex.getCode()).isEqualTo("setup_incomplete");
              assertThat(ex.getStatus().value()).isEqualTo(400);
            });
    verify(userRepository, never()).save(any());
  }

  @Test
  void refusesWhenAlreadySeededWithoutForce() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of(DemoSeedService.DEMO_SEEDED_KEY, true));
    assertThatThrownBy(() -> service.seed(false))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              ApiException ex = (ApiException) e;
              assertThat(ex.getCode()).isEqualTo("demo_seeded");
              assertThat(ex.getStatus().value()).isEqualTo(409);
            });
    verify(userRepository, never()).save(any());
  }

  @Test
  void forceClearsPreviousDemoDataAndReseeds() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of(DemoSeedService.DEMO_SEEDED_KEY, true));
    User alice = User.builder().id("a1").username("alice").createdAt(Instant.now()).build();
    Group devops = Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build();
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
    when(groupRepository.findByName("DevOps")).thenReturn(Optional.of(devops));
    when(certificateRepository.findByUserId("a1")).thenReturn(List.of());
    when(memberRepository.findById_UserId("a1")).thenReturn(List.of());
    when(settingsService.userSettings("a1")).thenReturn(Map.of());
    when(connectionLogRepository.findAll()).thenReturn(List.of());
    when(settingsService.groupSettings("g1")).thenReturn(Map.of());
    when(ruleRepository.findAll()).thenReturn(List.of());
    when(recordRepository.findByHostnameIgnoreCase(any())).thenReturn(Optional.empty());

    int users = service.seed(true);

    assertThat(users).isEqualTo(4);
    verify(userRepository).delete(alice);
    verify(groupRepository).delete(devops);
    verify(accessRuleService).deleteForUser("a1");
    verify(ccdService).clearStaticIp("a1");
    verify(ccdService).clearStaticIpv6("a1");
    verify(settingsService).deleteServerSetting(DemoSeedService.DEMO_SEEDED_KEY);
    verify(userRepository, times(4)).save(any(User.class));
  }

  @Test
  void refusesToDeleteUnknownUserDuringForceClear() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of(DemoSeedService.DEMO_SEEDED_KEY, true));
    when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
    when(groupRepository.findByName(any())).thenReturn(Optional.empty());
    when(recordRepository.findByHostnameIgnoreCase(any())).thenReturn(Optional.empty());
    when(ruleRepository.findAll()).thenReturn(List.of());

    assertThat(service.seed(true)).isEqualTo(4);
  }
}
