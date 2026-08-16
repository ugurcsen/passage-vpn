package com.opnl.vpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.access.AccessRuleService;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.auth.TotpService;
import com.opnl.vpn.ccd.CcdService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.Group;
import com.opnl.vpn.group.GroupAdminAssignment;
import com.opnl.vpn.group.GroupAdminAssignmentRepository;
import com.opnl.vpn.group.GroupMember;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.group.GroupScope;
import com.opnl.vpn.pki.CertService;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserAdminServiceTest {

  private UserRepository userRepository;
  private GroupRepository groupRepository;
  private GroupMemberRepository memberRepository;
  private GroupAdminAssignmentRepository adminAssignmentRepository;
  private GroupScope groupScope;
  private SettingsService settingsService;
  private CcdService ccdService;
  private CertService certService;
  private AccessRuleService accessRuleService;
  private UserAdminService service;

  private User admin() {
    return User.builder()
        .id("admin1")
        .username("admin")
        .role(User.Role.ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  private User groupAdmin() {
    return User.builder()
        .id("gadmin1")
        .username("gadmin")
        .role(User.Role.GROUP_ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    groupRepository = mock(GroupRepository.class);
    memberRepository = mock(GroupMemberRepository.class);
    adminAssignmentRepository = mock(GroupAdminAssignmentRepository.class);
    settingsService = mock(SettingsService.class);
    ccdService = mock(CcdService.class);
    certService = mock(CertService.class);
    accessRuleService = mock(AccessRuleService.class);
    groupScope = mock(GroupScope.class);
    service =
        new UserAdminService(
            userRepository,
            groupRepository,
            memberRepository,
            adminAssignmentRepository,
            groupScope,
            new BCryptPasswordEncoder(),
            new TotpService(),
            settingsService,
            ccdService,
            certService,
            accessRuleService,
            mock(AuditLogService.class));
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(1L);
    when(memberRepository.findById_UserId(any())).thenReturn(List.of());
    when(settingsService.userSettings(any())).thenReturn(new java.util.HashMap<>());
    when(settingsService.effectiveForUsers(any())).thenReturn(new java.util.HashMap<>());
    when(groupScope.isAdmin(any()))
        .thenAnswer(inv -> ((User) inv.getArgument(0)).getRole() == User.Role.ADMIN);
    when(groupScope.scopedUserIds(any()))
        .thenAnswer(
            inv -> {
              User actor = inv.getArgument(0);
              return actor.getRole() == User.Role.ADMIN ? null : Set.of("u2");
            });
    when(groupScope.managesUser(any(), any()))
        .thenAnswer(
            inv -> {
              User actor = inv.getArgument(0);
              return actor.getRole() == User.Role.ADMIN || "u2".equals(inv.getArgument(1));
            });
    when(groupScope.managesGroup(any(), anyString()))
        .thenAnswer(
            inv -> {
              User actor = inv.getArgument(0);
              return actor.getRole() == User.Role.ADMIN || "g1".equals(inv.getArgument(1));
            });
  }

  @Test
  void createUserRejectsDuplicateUsername() {
    when(userRepository.existsByUsername("bob")).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.createUser(
                    admin(),
                    new UserCreateRequest(
                        "bob", "supersecret1", null, null, User.Role.USER, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("username_taken"));
  }

  @Test
  void groupAdminCannotGrantAdminRole() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    groupAdmin(),
                    new UserCreateRequest(
                        "bob", "supersecret1", null, null, User.Role.ADMIN, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
    verify(userRepository, never()).save(any());
  }

  @Test
  void groupAdminCannotGrantGroupAdminRole() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    groupAdmin(),
                    new UserCreateRequest(
                        "bob",
                        "supersecret1",
                        null,
                        null,
                        User.Role.GROUP_ADMIN,
                        null,
                        List.of("g1"))))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
    verify(userRepository, never()).save(any());
  }

  @Test
  void createGroupAdminWithoutManagedGroupsIsRejected() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    admin(),
                    new UserCreateRequest(
                        "dave", "supersecret1", null, null, User.Role.GROUP_ADMIN, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> assertThat(((ApiException) e).getCode()).isEqualTo("admin_groups_required"));
    verify(userRepository, never()).save(any());
  }

  @Test
  void createGroupAdminPersistsAssignments() {
    java.util.concurrent.atomic.AtomicReference<User> saved =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(userRepository.save(any()))
        .thenAnswer(
            inv -> {
              saved.set(inv.getArgument(0));
              return inv.getArgument(0);
            });
    when(userRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(saved.get()));
    when(userRepository.existsByUsername(any())).thenReturn(false);
    when(groupRepository.findById("g1"))
        .thenReturn(
            Optional.of(
                com.opnl.vpn.group.Group.builder()
                    .id("g1")
                    .name("DevOps")
                    .createdAt(Instant.now())
                    .build()));

    service.createUser(
        admin(),
        new UserCreateRequest(
            "dave", "supersecret1", null, null, User.Role.GROUP_ADMIN, null, List.of("g1")));

    verify(adminAssignmentRepository).save(any());
    verify(adminAssignmentRepository).deleteAll(any());
  }

  @Test
  void lastAdminCannotBeDeletedByAnotherAdmin() {
    User other =
        User.builder()
            .id("admin2")
            .username("other")
            .role(User.Role.ADMIN)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("admin1")).thenReturn(Optional.of(admin()));
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(1L);
    assertThatThrownBy(() -> service.deleteUser(other, "admin1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("last_admin"));
  }

  @Test
  void userCannotDeleteSelf() {
    when(userRepository.findById("admin1")).thenReturn(Optional.of(admin()));
    assertThatThrownBy(() -> service.deleteUser(admin(), "admin1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("cannot_delete_self"));
  }

  private User bob() {
    return User.builder()
        .id("u2")
        .username("bob")
        .role(User.Role.USER)
        .createdAt(Instant.now())
        .build();
  }

  private String currentCode(String secret) throws Exception {
    return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
        .generate(secret, new SystemTimeProvider().getTime() / 30L);
  }

  @Test
  void deleteUserWithCertificateCleanupPurgesCertificates() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    service.deleteUser(admin(), "u2", new UserAdminService.DeleteOptions(true, false, false));
    verify(certService).purgeForUser("u2");
    verify(certService, never()).deleteRowsForUser(any());
    verify(userRepository).delete(any());
  }

  @Test
  void deleteUserWithAccessRuleCleanupDeletesRules() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    service.deleteUser(admin(), "u2", new UserAdminService.DeleteOptions(false, true, false));
    verify(accessRuleService).deleteForUser("u2");
    verify(userRepository).delete(any());
  }

  @Test
  void deleteUserWithCcdCleanupClearsStaticIp() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    service.deleteUser(admin(), "u2", new UserAdminService.DeleteOptions(false, false, true));
    verify(ccdService).clearStaticIp("u2");
    verify(ccdService).clearStaticIpv6("u2");
    verify(userRepository).delete(any());
  }

  @Test
  void deleteUserWithoutOptionsStillRemovesCertificateRows() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    service.deleteUser(admin(), "u2");
    verify(certService).deleteRowsForUser("u2");
    verify(certService, never()).purgeForUser(any());
    verify(accessRuleService, never()).deleteForUser(any());
    verify(ccdService, never()).clearStaticIp(any());
    verify(ccdService, never()).clearStaticIpv6(any());
    verify(userRepository).delete(any());
  }

  @Test
  void deleteGroupAdminCleansAssignments() {
    User gadmin =
        User.builder()
            .id("gadmin1")
            .username("gadmin")
            .role(User.Role.GROUP_ADMIN)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("gadmin1")).thenReturn(Optional.of(gadmin));
    service.deleteUser(admin(), "gadmin1");
    verify(adminAssignmentRepository).deleteAll(any());
    verify(userRepository).delete(gadmin);
  }

  @Test
  void bulkDeleteForwardsCleanupOptions() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    service.bulk(
        admin(),
        UserAdminService.BulkAction.DELETE,
        List.of("u2"),
        new UserAdminService.DeleteOptions(true, true, true));
    verify(certService).purgeForUser("u2");
    verify(accessRuleService).deleteForUser("u2");
    verify(ccdService).clearStaticIp("u2");
    verify(ccdService).clearStaticIpv6("u2");
    verify(userRepository).delete(any());
  }

  @Test
  void bannedFlagCanBeToggledForNonAdmins() {
    User bob =
        User.builder()
            .id("u2")
            .username("bob")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));
    service.setBanned(admin(), "u2", true);
    assertThat(bob.isBanned()).isTrue();
    verify(userRepository).save(bob);
  }

  @Test
  void resetPasswordUpdatesHash() {
    User bob =
        User.builder()
            .id("u2")
            .username("bob")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));
    service.resetPassword(admin(), "u2", "brandnewpass1");
    assertThat(bob.getPasswordHash()).isNotBlank();
    verify(userRepository).save(bob);
  }

  @Test
  void listUsersFiltersByUsernameFullNameOrEmail() {
    User alice =
        User.builder()
            .id("u1")
            .username("alice")
            .fullName("Alice Wonder")
            .email("alice@example.com")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    User bob =
        User.builder()
            .id("u2")
            .username("bob")
            .fullName("Robert Smith")
            .email("bob@corp.io")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findAll()).thenReturn(List.of(alice, bob));
    when(groupRepository.findAll()).thenReturn(List.of());
    when(memberRepository.findAll()).thenReturn(List.of());
    when(settingsService.effectiveForUsers(any()))
        .thenReturn(java.util.Map.of("u1", java.util.Map.of(), "u2", java.util.Map.of()));

    assertThat(service.listUsers(admin(), "ali"))
        .extracting(UserDto::username)
        .containsExactly("alice");
    assertThat(service.listUsers(admin(), "robert"))
        .extracting(UserDto::username)
        .containsExactly("bob");
    assertThat(service.listUsers(admin(), "corp.io"))
        .extracting(UserDto::username)
        .containsExactly("bob");
    assertThat(service.listUsers(admin(), null))
        .extracting(UserDto::username)
        .containsExactly("alice", "bob");
  }

  @Test
  void groupAdminSeesOnlyScopedUsers() {
    User alice =
        User.builder()
            .id("u1")
            .username("alice")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    User bob =
        User.builder()
            .id("u2")
            .username("bob")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findAll()).thenReturn(List.of(alice, bob));
    when(groupRepository.findAll()).thenReturn(List.of());
    when(memberRepository.findAll()).thenReturn(List.of());
    when(settingsService.effectiveForUsers(any()))
        .thenReturn(java.util.Map.of("u1", java.util.Map.of(), "u2", java.util.Map.of()));

    assertThat(service.listUsers(groupAdmin(), null))
        .extracting(UserDto::username)
        .containsExactly("bob");
  }

  @Test
  void listUsersResolvesSettingsInOneBatch() {
    when(userRepository.findAll()).thenReturn(List.of(bob()));
    when(groupRepository.findAll()).thenReturn(List.of());
    when(memberRepository.findAll()).thenReturn(List.of());
    when(settingsService.effectiveForUsers(any()))
        .thenReturn(
            java.util.Map.of(
                "u2",
                java.util.Map.of(
                    com.opnl.vpn.setting.SettingKeys.REQUIRE_MFA,
                    true,
                    com.opnl.vpn.setting.SettingKeys.MUST_CHANGE_PASSWORD,
                    true)));

    List<UserDto> dtos = service.listUsers(admin(), null);

    assertThat(dtos).hasSize(1);
    assertThat(dtos.get(0).mfaRequired()).isTrue();
    assertThat(dtos.get(0).mustChangePassword()).isTrue();
    verify(settingsService, never()).userSettings(any());
    verify(settingsService, never()).effectiveForUser(any());
  }

  @Test
  void bulkBansMultipleUsers() {
    User bob =
        User.builder()
            .id("u2")
            .username("bob")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    User carol =
        User.builder()
            .id("u3")
            .username("carol")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));
    when(userRepository.findById("u3")).thenReturn(Optional.of(carol));
    int affected = service.bulk(admin(), UserAdminService.BulkAction.BAN, List.of("u2", "u3"));
    assertThat(affected).isEqualTo(2);
    assertThat(bob.isBanned()).isTrue();
    assertThat(carol.isBanned()).isTrue();
    verify(userRepository, org.mockito.Mockito.times(2)).save(any());
  }

  @Test
  void bulkRejectsEmptyBatch() {
    assertThatThrownBy(() -> service.bulk(admin(), UserAdminService.BulkAction.BAN, List.of()))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("empty_batch"));
  }

  @Test
  void bulkUnbansAndDeletesUsers() {
    User bob =
        User.builder()
            .id("u2")
            .username("bob")
            .role(User.Role.USER)
            .banned(true)
            .createdAt(Instant.now())
            .build();
    User carol =
        User.builder()
            .id("u3")
            .username("carol")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));
    when(userRepository.findById("u3")).thenReturn(Optional.of(carol));

    service.bulk(admin(), UserAdminService.BulkAction.UNBAN, List.of("u2"));
    assertThat(bob.isBanned()).isFalse();

    service.bulk(admin(), UserAdminService.BulkAction.DELETE, List.of("u2", "u3"));
    verify(memberRepository, org.mockito.Mockito.times(2)).deleteAll(any());
    verify(userRepository).delete(bob);
    verify(userRepository).delete(carol);
  }

  @Test
  void lastAdminCannotBeBanned() {
    when(userRepository.findById("admin1")).thenReturn(Optional.of(admin()));
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(1L);
    assertThatThrownBy(() -> service.setBanned(admin(), "admin1", true))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("last_admin"));
  }

  @Test
  void groupAdminCannotManageAdminOrGroupAdminAccounts() {
    when(userRepository.findById("admin1")).thenReturn(Optional.of(admin()));
    when(userRepository.findById("gadmin1")).thenReturn(Optional.of(groupAdmin()));

    assertThatThrownBy(() -> service.resetPassword(groupAdmin(), "admin1", "pwned123!"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
    assertThatThrownBy(() -> service.setBanned(groupAdmin(), "admin1", true))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
    assertThatThrownBy(() -> service.deleteUser(groupAdmin(), "admin1"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
    assertThatThrownBy(() -> service.resetPassword(groupAdmin(), "gadmin1", "pwned123!"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
    assertThatThrownBy(() -> service.setStaticIp(groupAdmin(), "gadmin1", "10.8.0.200"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
  }

  @Test
  void groupAdminCanManageUserAccountsInScope() {
    User bob =
        User.builder()
            .id("u2")
            .username("bob")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));

    service.resetPassword(groupAdmin(), "u2", "brandnewpass1");
    service.setBanned(groupAdmin(), "u2", true);
    verify(userRepository, org.mockito.Mockito.times(2)).save(bob);
  }

  @Test
  void createUserTrimsUsername() {
    java.util.concurrent.atomic.AtomicReference<User> saved =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(userRepository.save(any()))
        .thenAnswer(
            inv -> {
              saved.set(inv.getArgument(0));
              return inv.getArgument(0);
            });
    when(userRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(saved.get()));
    service.createUser(
        admin(),
        new UserCreateRequest("  bob  ", "supersecret1", null, null, User.Role.USER, null, null));
    org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    assertThat(captor.getValue().getUsername()).isEqualTo("bob");
  }

  @Test
  void createUserPersistsMemberships() {
    java.util.concurrent.atomic.AtomicReference<User> saved =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(userRepository.existsByUsername(any())).thenReturn(false);
    when(userRepository.save(any()))
        .thenAnswer(
            inv -> {
              saved.set(inv.getArgument(0));
              return inv.getArgument(0);
            });
    when(userRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(saved.get()));
    when(groupRepository.findById("g1"))
        .thenReturn(
            Optional.of(Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build()));

    UserDto dto =
        service.createUser(
            admin(),
            new UserCreateRequest(
                "dave", "supersecret1", null, null, User.Role.USER, List.of("g1"), null));

    assertThat(dto.username()).isEqualTo("dave");
    verify(memberRepository).save(any(GroupMember.class));
  }

  @Test
  void createUserRejectsOutOfScopeGroup() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    groupAdmin(),
                    new UserCreateRequest(
                        "dave", "supersecret1", null, null, User.Role.USER, List.of("g2"), null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
  }

  @Test
  void createUserRejectsUnknownGroup() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    admin(),
                    new UserCreateRequest(
                        "dave", "supersecret1", null, null, User.Role.USER, List.of("g9"), null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("group_not_found"));
  }

  @Test
  void getUserResolvesMembershipsAndAdminNames() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    when(memberRepository.findById_UserId("u2")).thenReturn(List.of(new GroupMember("g1", "u2")));
    when(adminAssignmentRepository.findById_UserId("u2"))
        .thenReturn(List.of(new GroupAdminAssignment("u2", "g1")));
    when(groupRepository.findById("g1"))
        .thenReturn(
            Optional.of(Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build()));

    UserDto dto = service.getUser(admin(), "u2");

    assertThat(dto.username()).isEqualTo("bob");
    assertThat(dto.groups()).containsExactly("DevOps");
    assertThat(dto.adminGroupIds()).containsExactly("g1");
    assertThat(dto.adminGroupNames()).containsExactly("DevOps");
  }

  @Test
  void getUserThrowsWhenUserNotFound() {
    assertThatThrownBy(() -> service.getUser(admin(), "u2"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found"));
  }

  @Test
  void getUserReportsMustChangePassword() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    java.util.Map<String, Object> settings = new java.util.HashMap<>();
    settings.put(SettingKeys.MUST_CHANGE_PASSWORD, true);
    when(settingsService.userSettings("u2")).thenReturn(settings);

    UserDto dto = service.getUser(admin(), "u2");

    assertThat(dto.mustChangePassword()).isTrue();
  }

  @Test
  void updateUserUpdatesFieldsAndPassword() {
    User bob = bob();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.updateUser(
        admin(),
        "u2",
        new UserUpdateRequest("Robert", "bob@corp.io", null, null, "brandnewpass1", null, null));

    assertThat(bob.getFullName()).isEqualTo("Robert");
    assertThat(bob.getEmail()).isEqualTo("bob@corp.io");
    assertThat(bob.getPasswordHash()).isNotBlank();
    verify(settingsService).setUserSetting("u2", SettingKeys.MUST_CHANGE_PASSWORD, true);
  }

  @Test
  void updateUserRejectsDemotingLastAdmin() {
    when(userRepository.findById("admin1")).thenReturn(Optional.of(admin()));
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(1L);

    assertThatThrownBy(
            () ->
                service.updateUser(
                    admin(),
                    "admin1",
                    new UserUpdateRequest(null, null, User.Role.USER, null, null, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("last_admin"));
  }

  @Test
  void updateUserRejectsBanningLastAdmin() {
    when(userRepository.findById("admin1")).thenReturn(Optional.of(admin()));
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(1L);

    assertThatThrownBy(
            () ->
                service.updateUser(
                    admin(),
                    "admin1",
                    new UserUpdateRequest(null, null, null, true, null, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("last_admin"));
  }

  @Test
  void updateUserToGroupAdminWithoutGroupsIsRejected() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));

    assertThatThrownBy(
            () ->
                service.updateUser(
                    admin(),
                    "u2",
                    new UserUpdateRequest(
                        null, null, User.Role.GROUP_ADMIN, null, null, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> assertThat(((ApiException) e).getCode()).isEqualTo("admin_groups_required"));
  }

  @Test
  void updateUserPromoteToGroupAdminPersistsAssignments() {
    User bob = bob();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(groupRepository.findById("g1"))
        .thenReturn(
            Optional.of(Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build()));

    service.updateUser(
        admin(),
        "u2",
        new UserUpdateRequest(null, null, User.Role.GROUP_ADMIN, null, null, null, List.of("g1")));

    assertThat(bob.getRole()).isEqualTo(User.Role.GROUP_ADMIN);
    verify(adminAssignmentRepository).save(any(GroupAdminAssignment.class));
  }

  @Test
  void groupAdminCannotUpdateOutOfScopeUser() {
    User alice =
        User.builder()
            .id("u1")
            .username("alice")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("u1")).thenReturn(Optional.of(alice));

    assertThatThrownBy(
            () ->
                service.updateUser(
                    groupAdmin(),
                    "u1",
                    new UserUpdateRequest("X", null, null, null, null, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
  }

  @Test
  void deleteUserDeletesUserSettings() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    java.util.Map<String, Object> settings = new java.util.HashMap<>();
    settings.put("key1", "v1");
    settings.put("key2", "v2");
    when(settingsService.userSettings("u2")).thenReturn(settings);

    service.deleteUser(admin(), "u2");

    verify(settingsService).deleteUserSetting("u2", "key1");
    verify(settingsService).deleteUserSetting("u2", "key2");
    ArgumentCaptor<User> deleted = ArgumentCaptor.forClass(User.class);
    verify(userRepository).delete(deleted.capture());
    assertThat(deleted.getValue().getId()).isEqualTo("u2");
    assertThat(deleted.getValue().getUsername()).isEqualTo("bob");
  }

  @Test
  void deleteUserThrowsWhenUserNotFound() {
    assertThatThrownBy(() -> service.deleteUser(admin(), "u2"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found"));
  }

  @Test
  void setBannedUnbansUser() {
    User bob =
        User.builder()
            .id("u2")
            .username("bob")
            .role(User.Role.USER)
            .banned(true)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));

    service.setBanned(admin(), "u2", false);

    assertThat(bob.isBanned()).isFalse();
    verify(userRepository).save(bob);
  }

  @Test
  void staticIpOperationsDelegateToCcdService() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));

    service.setStaticIp(admin(), "u2", "10.8.0.100");
    service.allocateStaticIp(admin(), "u2");
    service.clearStaticIp(admin(), "u2");
    service.setStaticIpv6(admin(), "u2", "fd00:1::10");
    service.allocateStaticIpv6(admin(), "u2");
    service.clearStaticIpv6(admin(), "u2");

    verify(ccdService).setStaticIp("u2", "10.8.0.100");
    verify(ccdService).allocateFromGroupPool("u2");
    verify(ccdService).clearStaticIp("u2");
    verify(ccdService).setStaticIpv6("u2", "fd00:1::10");
    verify(ccdService).allocateIpv6FromGroupPool("u2");
    verify(ccdService).clearStaticIpv6("u2");
  }

  @Test
  void setupMfaGeneratesSecretAndStoresIt() {
    User bob = bob();
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));

    UserAdminService.MfaSetup setup = service.setupMfa("u2");

    assertThat(setup.secret()).isNotBlank();
    assertThat(setup.otpAuthUrl()).contains("otpauth://");
    assertThat(setup.qrDataUrl()).startsWith("data:image/png;base64,");
    assertThat(bob.getMfaSecret()).isEqualTo(setup.secret());
    assertThat(bob.isMfaEnabled()).isFalse();
    verify(userRepository).save(bob);
  }

  @Test
  void enableMfaActivatesMfaWithValidCode() throws Exception {
    String secret = new DefaultSecretGenerator(160).generate();
    User bob = bob();
    bob.setMfaSecret(secret);
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.enableMfa(admin(), "u2", currentCode(secret));

    assertThat(bob.isMfaEnabled()).isTrue();
    verify(userRepository).save(bob);
  }

  @Test
  void enableMfaRejectsInvalidCode() {
    User bob = bob();
    bob.setMfaSecret(new DefaultSecretGenerator(160).generate());
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));

    assertThatThrownBy(() -> service.enableMfa(admin(), "u2", "000000"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_code"));
  }

  @Test
  void enableMfaRejectsWhenNoSecretProvisioned() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));

    assertThatThrownBy(() -> service.enableMfa(admin(), "u2", "000000"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("invalid_code"));
  }

  @Test
  void disableMfaClearsSecret() {
    User bob = bob();
    bob.setMfaSecret("secret");
    bob.setMfaEnabled(true);
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob));

    service.disableMfa(admin(), "u2");

    assertThat(bob.isMfaEnabled()).isFalse();
    assertThat(bob.getMfaSecret()).isNull();
    verify(userRepository).save(bob);
  }

  @Test
  void disableMfaRejectedWhenRequiredByPolicy() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    when(settingsService.effectiveForUser("u2"))
        .thenReturn(java.util.Map.of(SettingKeys.REQUIRE_MFA, true));

    assertThatThrownBy(() -> service.disableMfa(admin(), "u2"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("mfa_required"));
  }

  @Test
  void userSettingsDelegatesToSettingsService() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    java.util.Map<String, Object> settings = new java.util.HashMap<>();
    settings.put("k", "v");
    when(settingsService.userSettings("u2")).thenReturn(settings);

    assertThat(service.userSettings(admin(), "u2")).containsEntry("k", "v");
  }

  @Test
  void effectiveSettingsDelegatesToSettingsService() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    when(settingsService.effectiveForUser("u2"))
        .thenReturn(java.util.Map.of(SettingKeys.TUNNEL_MODE, "full"));

    assertThat(service.effectiveSettings(admin(), "u2"))
        .containsEntry(SettingKeys.TUNNEL_MODE, "full");
  }

  @Test
  void setUserSettingDelegatesAndReturnsUpdatedSettings() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    when(settingsService.userSettings("u2"))
        .thenReturn(new java.util.HashMap<>(java.util.Map.of("k", "new")));

    java.util.Map<String, Object> result = service.setUserSetting(admin(), "u2", "k", "new");

    verify(settingsService).setUserSetting("u2", "k", "new");
    assertThat(result).containsEntry("k", "new");
  }

  @Test
  void deleteUserSettingDelegatesAndReturnsUpdatedSettings() {
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));

    service.deleteUserSetting(admin(), "u2", "k");

    verify(settingsService).deleteUserSetting("u2", "k");
  }

  @Test
  void listUsersResolvesGroupAndAdminNames() {
    when(userRepository.findAll()).thenReturn(List.of(bob()));
    when(groupRepository.findAll())
        .thenReturn(
            List.of(Group.builder().id("g1").name("DevOps").createdAt(Instant.now()).build()));
    when(memberRepository.findAll()).thenReturn(List.of(new GroupMember("g1", "u2")));
    when(adminAssignmentRepository.findAll())
        .thenReturn(List.of(new GroupAdminAssignment("u2", "g1")));
    when(settingsService.effectiveForUsers(any()))
        .thenReturn(java.util.Map.of("u2", java.util.Map.of()));

    List<UserDto> dtos = service.listUsers(admin(), "  BOB  ");

    assertThat(dtos).extracting(UserDto::username).containsExactly("bob");
    assertThat(dtos.get(0).groups()).containsExactly("DevOps");
    assertThat(dtos.get(0).adminGroupIds()).containsExactly("g1");
    assertThat(dtos.get(0).adminGroupNames()).containsExactly("DevOps");
  }
}
