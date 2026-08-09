package com.opnl.vpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.auth.TotpService;
import com.opnl.vpn.ccd.CcdService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.group.GroupMemberRepository;
import com.opnl.vpn.group.GroupRepository;
import com.opnl.vpn.setting.SettingsService;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserAdminServiceTest {

  private UserRepository userRepository;
  private GroupRepository groupRepository;
  private GroupMemberRepository memberRepository;
  private SettingsService settingsService;
  private UserAdminService service;

  private User admin() {
    return User.builder()
        .id("admin1")
        .username("admin")
        .role(User.Role.ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  private User reseller() {
    return User.builder()
        .id("res1")
        .username("reseller")
        .role(User.Role.RESELLER)
        .createdAt(Instant.now())
        .build();
  }

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    groupRepository = mock(GroupRepository.class);
    memberRepository = mock(GroupMemberRepository.class);
    settingsService = mock(SettingsService.class);
    service =
        new UserAdminService(
            userRepository,
            groupRepository,
            memberRepository,
            new BCryptPasswordEncoder(),
            new TotpService(),
            settingsService,
            mock(CcdService.class));
    when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(1L);
    when(memberRepository.findById_UserId(any())).thenReturn(List.of());
  }

  @Test
  void createUserRejectsDuplicateUsername() {
    when(userRepository.existsByUsername("bob")).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.createUser(
                    admin(),
                    new UserCreateRequest("bob", "supersecret1", null, null, User.Role.USER, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("username_taken"));
  }

  @Test
  void resellerCannotGrantAdminRole() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    reseller(),
                    new UserCreateRequest(
                        "bob", "supersecret1", null, null, User.Role.ADMIN, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
    verify(userRepository, never()).save(any());
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
    service.setBanned("u2", true);
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
    service.resetPassword("u2", "brandnewpass1");
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
    when(settingsService.userSettings(any())).thenReturn(java.util.Map.of());

    assertThat(service.listUsers("ali")).extracting(UserDto::username).containsExactly("alice");
    assertThat(service.listUsers("robert")).extracting(UserDto::username).containsExactly("bob");
    assertThat(service.listUsers("corp.io")).extracting(UserDto::username).containsExactly("bob");
    assertThat(service.listUsers(null))
        .extracting(UserDto::username)
        .containsExactly("alice", "bob");
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
}
