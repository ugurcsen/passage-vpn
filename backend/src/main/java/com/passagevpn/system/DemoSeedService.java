package com.passagevpn.system;

import com.passagevpn.access.AccessRule;
import com.passagevpn.access.AccessRuleDto;
import com.passagevpn.access.AccessRuleRepository;
import com.passagevpn.access.AccessRuleService;
import com.passagevpn.audit.AuditLogService;
import com.passagevpn.ccd.CcdService;
import com.passagevpn.common.ApiException;
import com.passagevpn.dns.DnsOverrideService;
import com.passagevpn.dns.DnsRecord;
import com.passagevpn.dns.DnsRecordDto;
import com.passagevpn.dns.DnsRecordRepository;
import com.passagevpn.group.Group;
import com.passagevpn.group.GroupAdminAssignment;
import com.passagevpn.group.GroupAdminAssignmentRepository;
import com.passagevpn.group.GroupMember;
import com.passagevpn.group.GroupMemberRepository;
import com.passagevpn.group.GroupRepository;
import com.passagevpn.monitor.ConnectionLog;
import com.passagevpn.monitor.ConnectionLogRepository;
import com.passagevpn.pki.Certificate;
import com.passagevpn.pki.CertificateRepository;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import com.passagevpn.setup.SetupService;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a reproducible demo dataset: sample groups with static IP pools, users with settings, a
 * handful of access rules, DNS overrides, certificate bookkeeping rows and a small connection
 * history. Exposed to admins via the UI and to the operator via {@code POST /internal/seed-demo}
 * ({@code make seed-demo}) or automatic first-boot seeding when {@code PASSAGE_DEMO_MODE=true}.
 *
 * <p>Seeding is idempotent by default (a {@code demo.seeded} server setting marks a completed
 * seed); {@code force} wipes the previously seeded sample data first so re-running stays
 * reproducible. Real client certificates are not issued — the rows are bookkeeping only, exactly
 * what the certificates page renders.
 */
@Slf4j
@Service
public class DemoSeedService {

  /** Server-setting marker set once demo data has been loaded. */
  public static final String DEMO_SEEDED_KEY = "demo.seeded";

  private static final String DEMO_PASSWORD = "demo-password-1";
  private static final String GROUP_DEVOPS = "DevOps";
  private static final String GROUP_MARKETING = "Marketing";
  private static final List<String> DEMO_USERNAMES = List.of("alice", "bob", "carol", "dave");
  private static final List<String> DEMO_GROUP_NAMES = List.of(GROUP_DEVOPS, GROUP_MARKETING);

  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final GroupMemberRepository memberRepository;
  private final GroupAdminAssignmentRepository adminAssignmentRepository;
  private final PasswordEncoder passwordEncoder;
  private final SettingsService settingsService;
  private final CcdService ccdService;
  private final AccessRuleService accessRuleService;
  private final AccessRuleRepository ruleRepository;
  private final DnsOverrideService dnsOverrideService;
  private final DnsRecordRepository recordRepository;
  private final CertificateRepository certificateRepository;
  private final ConnectionLogRepository connectionLogRepository;
  private final SetupService setupService;
  private final AuditLogService auditLogService;

  public DemoSeedService(
      UserRepository userRepository,
      GroupRepository groupRepository,
      GroupMemberRepository memberRepository,
      GroupAdminAssignmentRepository adminAssignmentRepository,
      PasswordEncoder passwordEncoder,
      SettingsService settingsService,
      CcdService ccdService,
      AccessRuleService accessRuleService,
      AccessRuleRepository ruleRepository,
      DnsOverrideService dnsOverrideService,
      DnsRecordRepository recordRepository,
      CertificateRepository certificateRepository,
      ConnectionLogRepository connectionLogRepository,
      SetupService setupService,
      AuditLogService auditLogService) {
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.memberRepository = memberRepository;
    this.adminAssignmentRepository = adminAssignmentRepository;
    this.passwordEncoder = passwordEncoder;
    this.settingsService = settingsService;
    this.ccdService = ccdService;
    this.accessRuleService = accessRuleService;
    this.ruleRepository = ruleRepository;
    this.dnsOverrideService = dnsOverrideService;
    this.recordRepository = recordRepository;
    this.certificateRepository = certificateRepository;
    this.connectionLogRepository = connectionLogRepository;
    this.setupService = setupService;
    this.auditLogService = auditLogService;
  }

  /** True when demo data has already been seeded. */
  @Transactional(readOnly = true)
  public boolean seeded() {
    Object value = settingsService.serverSettings().get(DEMO_SEEDED_KEY);
    return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
  }

  /**
   * Seeds the demo dataset. Refuses when setup is not complete and (without {@code force}) when
   * demo data is already loaded. Returns the number of sample users created.
   */
  @Transactional
  public int seed(boolean force) {
    if (!setupService.complete()) {
      throw ApiException.badRequest(
          "setup_incomplete", "Setup must be completed before loading demo data");
    }
    if (!force && seeded()) {
      throw ApiException.conflict("demo_seeded", "Demo data is already loaded");
    }
    if (force) {
      clearDemoData();
    }

    Group devops =
        createGroup(GROUP_DEVOPS, "Engineering access control group", "10.8.0.100-10.8.0.149");
    Group marketing =
        createGroup(
            GROUP_MARKETING, "Full-tunnel group with a public HTTPS deny", "10.8.0.150-10.8.0.199");

    User alice =
        createUser("alice", "Alice Johnson", "alice@example.com", User.Role.USER, devops.getId());
    User bob = createUser("bob", "Bob Smith", "bob@example.com", User.Role.USER, marketing.getId());
    User carol =
        createUser("carol", "Carol Williams", "carol@example.com", User.Role.USER, devops.getId());
    User dave = createUser("dave", "Dave Brown", "dave@example.com", User.Role.GROUP_ADMIN, null);
    adminAssignmentRepository.save(new GroupAdminAssignment(dave.getId(), devops.getId()));

    settingsService.setUserSetting(alice.getId(), SettingKeys.TUNNEL_MODE, "full");
    settingsService.setUserSetting(carol.getId(), SettingKeys.MAX_CONNECTIONS, 2);
    settingsService.setUserSetting(carol.getId(), SettingKeys.TUNNEL_MODE, "split");
    settingsService.setUserSetting(dave.getId(), SettingKeys.MAX_CONNECTIONS, 10);

    ccdService.setStaticIp(alice.getId(), "10.8.0.100");
    ccdService.setStaticIp(bob.getId(), "10.8.0.150");

    seedAccessRules(alice, devops, marketing);
    seedDnsOverrides(devops);
    seedCertificates(alice, bob, carol);
    seedConnectionHistory(alice, bob);

    settingsService.setServerSetting(DEMO_SEEDED_KEY, true);
    auditLogService.record(
        "DEMO_SEED", AuditLogService.CAT_SYSTEM, null, "system", Map.of("users", 4, "groups", 2));
    log.info("Demo data seeded: 4 users, 2 groups");
    return 4;
  }

  // ---- creation helpers ----------------------------------------------------

  private Group createGroup(String name, String description, String pool) {
    Group group =
        Group.builder()
            .id(UUID.randomUUID().toString())
            .name(name)
            .description(description)
            .createdAt(Instant.now())
            .build();
    groupRepository.save(group);
    settingsService.setGroupSetting(group.getId(), SettingKeys.STATIC_IP_POOL, pool);
    return group;
  }

  private User createUser(
      String username, String fullName, String email, User.Role role, String groupId) {
    User user =
        User.builder()
            .id(UUID.randomUUID().toString())
            .username(username)
            .passwordHash(passwordEncoder.encode(DEMO_PASSWORD))
            .fullName(fullName)
            .email(email)
            .role(role)
            .createdAt(Instant.now())
            .build();
    userRepository.save(user);
    if (groupId != null) {
      memberRepository.save(new GroupMember(groupId, user.getId()));
    }
    return user;
  }

  private void seedAccessRules(User alice, Group devops, Group marketing) {
    // VPN subnet reachable for everyone.
    accessRuleService.create(
        new AccessRuleDto(
            null,
            AccessRule.TargetType.GLOBAL,
            null,
            null,
            AccessRule.Action.ALLOW,
            null,
            "10.8.0.0/24",
            null,
            null,
            null,
            null,
            true,
            null,
            List.of()));
    // Corporate net (10.x) blocked for Marketing.
    accessRuleService.create(
        new AccessRuleDto(
            null,
            AccessRule.TargetType.GROUP,
            marketing.getId(),
            null,
            AccessRule.Action.DENY,
            null,
            "10.0.0.0/8",
            null,
            null,
            null,
            null,
            true,
            null,
            List.of()));
    // SSH to the internal git server for alice (and DevOps by extension of the ALLOW).
    accessRuleService.create(
        new AccessRuleDto(
            null,
            AccessRule.TargetType.USER,
            alice.getId(),
            null,
            AccessRule.Action.ALLOW,
            AccessRule.Protocol.TCP,
            "10.8.0.5/32",
            null,
            null,
            null,
            22,
            true,
            null,
            List.of()));
    // Marketing may browse HTTPS freely; everyone may reach the git.internal hostname.
    accessRuleService.create(
        new AccessRuleDto(
            null,
            AccessRule.TargetType.GLOBAL,
            null,
            null,
            AccessRule.Action.ALLOW,
            AccessRule.Protocol.TCP,
            null,
            null,
            null,
            "git.internal",
            null,
            true,
            null,
            List.of()));
  }

  private void seedDnsOverrides(Group devops) {
    dnsOverrideService.create(
        new DnsRecordDto(
            null,
            "git.internal",
            "10.8.0.5",
            null,
            DnsRecord.Scope.GLOBAL,
            null,
            null,
            true,
            null,
            List.of()));
    dnsOverrideService.create(
        new DnsRecordDto(
            null,
            "docs.internal",
            "10.8.0.6",
            null,
            DnsRecord.Scope.GROUP,
            devops.getId(),
            null,
            true,
            null,
            List.of()));
  }

  private void seedCertificates(User alice, User bob, User carol) {
    Instant now = Instant.now();
    certificateRepository.save(
        Certificate.builder()
            .id(UUID.randomUUID().toString())
            .commonName("alice")
            .userId(alice.getId())
            .status(Certificate.Status.VALID)
            .serial("11")
            .issuedAt(now.minus(Duration.ofDays(120)))
            .expiresAt(now.plus(Duration.ofDays(240)))
            .build());
    certificateRepository.save(
        Certificate.builder()
            .id(UUID.randomUUID().toString())
            .commonName("bob")
            .userId(bob.getId())
            .status(Certificate.Status.VALID)
            .serial("12")
            .issuedAt(now.minus(Duration.ofDays(90)))
            .expiresAt(now.plus(Duration.ofDays(275)))
            .build());
    certificateRepository.save(
        Certificate.builder()
            .id(UUID.randomUUID().toString())
            .commonName("carol")
            .userId(carol.getId())
            .status(Certificate.Status.REVOKED)
            .serial("13")
            .issuedAt(now.minus(Duration.ofDays(60)))
            .expiresAt(now.plus(Duration.ofDays(305)))
            .revokedAt(now.minus(Duration.ofDays(10)))
            .build());
  }

  private void seedConnectionHistory(User alice, User bob) {
    Instant now = Instant.now();
    connectionLogRepository.save(
        ConnectionLog.builder()
            .id(UUID.randomUUID().toString())
            .username("alice")
            .commonName("alice")
            .virtualIp("10.8.0.100")
            .remoteIp("203.0.113.10")
            .daemonName("daemon-0")
            .connectedAt(now.minus(Duration.ofMinutes(45)))
            .disconnectedAt(now.minus(Duration.ofMinutes(20)))
            .bytesIn(38_912_000)
            .bytesOut(4_210_000)
            .createdAt(now.minus(Duration.ofMinutes(45)))
            .build());
    connectionLogRepository.save(
        ConnectionLog.builder()
            .id(UUID.randomUUID().toString())
            .username("bob")
            .commonName("bob")
            .virtualIp("10.8.0.150")
            .remoteIp("198.51.100.42")
            .daemonName("daemon-0")
            .connectedAt(now.minus(Duration.ofDays(1)))
            .disconnectedAt(now.minus(Duration.ofDays(1)).plus(Duration.ofHours(2)))
            .bytesIn(1_240_000)
            .bytesOut(92_000)
            .createdAt(now.minus(Duration.ofDays(1)))
            .build());
  }

  // ---- force re-seed -------------------------------------------------------

  /** Removes every entity the demo seed creates (identified deterministically) so it can re-run. */
  private void clearDemoData() {
    for (String username : DEMO_USERNAMES) {
      userRepository.findByUsername(username).ifPresent(this::deleteUserWithDependencies);
    }
    for (String name : DEMO_GROUP_NAMES) {
      groupRepository.findByName(name).ifPresent(this::deleteGroupWithDependencies);
    }
    for (String hostname : List.of("git.internal", "docs.internal")) {
      recordRepository.findByHostnameIgnoreCase(hostname).ifPresent(recordRepository::delete);
    }
    ruleRepository.findAll().stream()
        .filter(rule -> rule.getTargetType() == AccessRule.TargetType.GLOBAL)
        .filter(
            rule ->
                "10.8.0.0/24".equals(rule.getDstCidr())
                    || "git.internal".equals(rule.getDstDomain()))
        .forEach(ruleRepository::delete);
    settingsService.deleteServerSetting(DEMO_SEEDED_KEY);
  }

  private void deleteUserWithDependencies(User user) {
    String id = user.getId();
    certificateRepository.findByUserId(id).forEach(certificateRepository::delete);
    memberRepository.deleteAll(memberRepository.findById_UserId(id));
    adminAssignmentRepository.deleteAll(adminAssignmentRepository.findById_UserId(id));
    settingsService
        .userSettings(id)
        .keySet()
        .forEach(key -> settingsService.deleteUserSetting(id, key));
    accessRuleService.deleteForUser(id);
    connectionLogRepository.findAll().stream()
        .filter(log -> user.getUsername().equals(log.getUsername()))
        .forEach(connectionLogRepository::delete);
    ccdService.clearStaticIp(id);
    ccdService.clearStaticIpv6(id);
    userRepository.delete(user);
  }

  private void deleteGroupWithDependencies(Group group) {
    String id = group.getId();
    memberRepository.deleteById_GroupId(id);
    adminAssignmentRepository.deleteById_GroupId(id);
    settingsService
        .groupSettings(id)
        .keySet()
        .forEach(key -> settingsService.deleteGroupSetting(id, key));
    ruleRepository.findAll().stream()
        .filter(
            rule ->
                (rule.getTargetType() == AccessRule.TargetType.GROUP
                        && id.equals(rule.getTargetId()))
                    || id.equals(rule.getDstGroupId()))
        .forEach(ruleRepository::delete);
    groupRepository.delete(group);
  }

  /** Result of a seed run: how many sample users were created. */
  public record SeedResult(int users) {}
}
