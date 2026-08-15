package com.opnl.vpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/** Web-layer tests for the admin user API (direct controller invocation with a mocked actor). */
class UserAdminControllerTest {

  private UserAdminService userAdminService;
  private UserRepository userRepository;
  private Authentication authentication;
  private UserAdminController controller;

  @BeforeEach
  void setUp() {
    userAdminService = mock(UserAdminService.class);
    userRepository = mock(UserRepository.class);
    authentication = mock(Authentication.class);
    when(authentication.getPrincipal()).thenReturn("admin1");
    when(userRepository.findById("admin1")).thenReturn(Optional.of(admin()));
    controller = new UserAdminController(userAdminService, userRepository);
  }

  private User admin() {
    return User.builder()
        .id("admin1")
        .username("admin")
        .role(User.Role.ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  private UserDto dto() {
    return UserDto.from(
        User.builder()
            .id("u1")
            .username("alice")
            .email("alice@example.com")
            .role(User.Role.USER)
            .createdAt(Instant.now())
            .build(),
        false,
        false,
        List.of("Engineering"),
        List.of(),
        List.of());
  }

  @Test
  void listDelegatesToService() {
    when(userAdminService.listUsers(any(), eq(null))).thenReturn(List.of(dto()));

    List<UserDto> result = controller.list(authentication, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).username()).isEqualTo("alice");
    verify(userAdminService).listUsers(any(), eq(null));
  }

  @Test
  void listForwardsSearchTerm() {
    when(userAdminService.listUsers(any(), eq("ali"))).thenReturn(List.of(dto()));

    List<UserDto> result = controller.list(authentication, "ali");

    assertThat(result).hasSize(1);
    verify(userAdminService).listUsers(any(), eq("ali"));
  }

  @Test
  void getDelegatesToService() {
    when(userAdminService.getUser(any(), eq("u1"))).thenReturn(dto());

    UserDto result = controller.get(authentication, "u1");

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).getUser(any(), eq("u1"));
  }

  @Test
  void getPropagatesUserNotFound() {
    when(userAdminService.getUser(any(), eq("ghost")))
        .thenThrow(ApiException.notFound("user_not_found", "User not found"));

    assertThatThrownBy(() -> controller.get(authentication, "ghost"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found");
            });
  }

  @Test
  void createDelegatesToService() {
    UserCreateRequest request =
        new UserCreateRequest("bob", "supersecret1", "Bob", null, User.Role.USER, null, null);
    when(userAdminService.createUser(any(), any())).thenReturn(dto());

    UserDto result = controller.create(authentication, request);

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).createUser(any(), eq(request));
  }

  @Test
  void createPropagatesUsernameTaken() {
    when(userAdminService.createUser(any(), any()))
        .thenThrow(ApiException.conflict("username_taken", "Username already exists"));

    assertThatThrownBy(
            () ->
                controller.create(
                    authentication,
                    new UserCreateRequest("bob", "supersecret1", null, null, null, null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(((ApiException) e).getCode()).isEqualTo("username_taken");
            });
  }

  @Test
  void updateDelegatesToService() {
    UserUpdateRequest request = new UserUpdateRequest("Bobby", null, null, null, null, null, null);
    when(userAdminService.updateUser(any(), eq("u1"), any())).thenReturn(dto());

    UserDto result = controller.update(authentication, "u1", request);

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).updateUser(any(), eq("u1"), eq(request));
  }

  @Test
  void deleteDelegatesToServiceWithoutOptions() {
    controller.delete(authentication, "u1", null);

    verify(userAdminService).deleteUser(any(), eq("u1"), eq(UserAdminService.DeleteOptions.none()));
  }

  @Test
  void deleteDelegatesToServiceWithOptions() {
    UserAdminService.DeleteOptions options = new UserAdminService.DeleteOptions(true, false, true);

    controller.delete(authentication, "u1", options);

    verify(userAdminService).deleteUser(any(), eq("u1"), eq(options));
  }

  @Test
  void deletePropagatesSelfDelete() {
    doThrow(ApiException.badRequest("cannot_delete_self", "You cannot delete your own account"))
        .when(userAdminService)
        .deleteUser(any(), eq("u1"), any());

    assertThatThrownBy(() -> controller.delete(authentication, "u1", null))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(((ApiException) e).getCode()).isEqualTo("cannot_delete_self");
            });
  }

  @Test
  void resetPasswordDelegatesToService() {
    controller.resetPassword(
        authentication, "u1", new UserAdminController.PasswordRequest("newpassword"));

    verify(userAdminService).resetPassword(any(), eq("u1"), eq("newpassword"));
  }

  @Test
  void banDelegatesToService() {
    when(userAdminService.setBanned(any(), eq("u1"), eq(true))).thenReturn(dto());

    UserDto result = controller.ban(authentication, "u1");

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).setBanned(any(), eq("u1"), eq(true));
  }

  @Test
  void unbanDelegatesToService() {
    when(userAdminService.setBanned(any(), eq("u1"), eq(false))).thenReturn(dto());

    UserDto result = controller.unban(authentication, "u1");

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).setBanned(any(), eq("u1"), eq(false));
  }

  @Test
  void mfaSetupDelegatesToService() {
    UserAdminService.MfaSetup setup =
        new UserAdminService.MfaSetup("secret", "otpauth://totp/", "data:image/png;base64,");
    when(userAdminService.setupMfa("u1")).thenReturn(setup);

    UserAdminService.MfaSetup result = controller.mfaSetup(authentication, "u1");

    assertThat(result.secret()).isEqualTo("secret");
    verify(userAdminService).setupMfa("u1");
  }

  @Test
  void mfaEnableDelegatesToService() {
    when(userAdminService.enableMfa(any(), eq("u1"), eq("123456"))).thenReturn(dto());

    UserDto result =
        controller.mfaEnable(
            authentication, "u1", new UserAdminController.MfaEnableRequest("123456"));

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).enableMfa(any(), eq("u1"), eq("123456"));
  }

  @Test
  void mfaDisableDelegatesToService() {
    when(userAdminService.disableMfa(any(), eq("u1"))).thenReturn(dto());

    UserDto result = controller.mfaDisable(authentication, "u1");

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).disableMfa(any(), eq("u1"));
  }

  @Test
  void setStaticIpDelegatesToService() {
    when(userAdminService.setStaticIp(any(), eq("u1"), eq("10.8.0.100"))).thenReturn(dto());

    UserDto result =
        controller.setStaticIp(
            authentication, "u1", new UserAdminController.StaticIpRequest("10.8.0.100"));

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).setStaticIp(any(), eq("u1"), eq("10.8.0.100"));
  }

  @Test
  void allocateStaticIpDelegatesToService() {
    when(userAdminService.allocateStaticIp(any(), eq("u1"))).thenReturn(dto());

    UserDto result = controller.allocateStaticIp(authentication, "u1");

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).allocateStaticIp(any(), eq("u1"));
  }

  @Test
  void clearStaticIpDelegatesToService() {
    when(userAdminService.clearStaticIp(any(), eq("u1"))).thenReturn(dto());

    UserDto result = controller.clearStaticIp(authentication, "u1");

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).clearStaticIp(any(), eq("u1"));
  }

  @Test
  void setStaticIpv6DelegatesToService() {
    when(userAdminService.setStaticIpv6(any(), eq("u1"), eq("fd00:1::10"))).thenReturn(dto());

    UserDto result =
        controller.setStaticIpv6(
            authentication, "u1", new UserAdminController.StaticIpv6Request("fd00:1::10"));

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).setStaticIpv6(any(), eq("u1"), eq("fd00:1::10"));
  }

  @Test
  void allocateStaticIpv6DelegatesToService() {
    when(userAdminService.allocateStaticIpv6(any(), eq("u1"))).thenReturn(dto());

    UserDto result = controller.allocateStaticIpv6(authentication, "u1");

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).allocateStaticIpv6(any(), eq("u1"));
  }

  @Test
  void clearStaticIpv6DelegatesToService() {
    when(userAdminService.clearStaticIpv6(any(), eq("u1"))).thenReturn(dto());

    UserDto result = controller.clearStaticIpv6(authentication, "u1");

    assertThat(result.username()).isEqualTo("alice");
    verify(userAdminService).clearStaticIpv6(any(), eq("u1"));
  }

  @Test
  void settingsDelegatesToService() {
    when(userAdminService.userSettings(any(), eq("u1")))
        .thenReturn(Map.of("dns_servers", "1.1.1.1"));

    Map<String, Object> result = controller.settings(authentication, "u1");

    assertThat(result).containsEntry("dns_servers", "1.1.1.1");
    verify(userAdminService).userSettings(any(), eq("u1"));
  }

  @Test
  void effectiveSettingsDelegatesToService() {
    when(userAdminService.effectiveSettings(any(), eq("u1")))
        .thenReturn(Map.of("tunnel_mode", "split"));

    Map<String, Object> result = controller.effectiveSettings(authentication, "u1");

    assertThat(result).containsEntry("tunnel_mode", "split");
    verify(userAdminService).effectiveSettings(any(), eq("u1"));
  }

  @Test
  void setSettingDelegatesToService() {
    when(userAdminService.setUserSetting(any(), eq("u1"), eq("dns_servers"), eq("1.1.1.1")))
        .thenReturn(Map.of("dns_servers", "1.1.1.1"));

    Map<String, Object> result =
        controller.setSetting(authentication, "u1", "dns_servers", "1.1.1.1");

    assertThat(result).containsEntry("dns_servers", "1.1.1.1");
    verify(userAdminService).setUserSetting(any(), eq("u1"), eq("dns_servers"), eq("1.1.1.1"));
  }

  @Test
  void deleteSettingDelegatesToService() {
    when(userAdminService.deleteUserSetting(any(), eq("u1"), eq("dns_servers")))
        .thenReturn(Map.of());

    Map<String, Object> result = controller.deleteSetting(authentication, "u1", "dns_servers");

    assertThat(result).isEmpty();
    verify(userAdminService).deleteUserSetting(any(), eq("u1"), eq("dns_servers"));
  }

  @Test
  void bulkDelegatesToServiceWithDefaultOptions() {
    when(userAdminService.bulk(any(), any(), any(), any())).thenReturn(2);

    int count =
        controller.bulk(
            authentication,
            new UserAdminController.BulkRequest(
                UserAdminService.BulkAction.BAN, List.of("u1", "u2"), null));

    assertThat(count).isEqualTo(2);
    verify(userAdminService)
        .bulk(
            any(),
            eq(UserAdminService.BulkAction.BAN),
            eq(List.of("u1", "u2")),
            eq(UserAdminService.DeleteOptions.none()));
  }

  @Test
  void bulkDelegatesToServiceWithOptions() {
    UserAdminService.DeleteOptions options = new UserAdminService.DeleteOptions(true, true, true);
    when(userAdminService.bulk(any(), any(), any(), any())).thenReturn(1);

    int count =
        controller.bulk(
            authentication,
            new UserAdminController.BulkRequest(
                UserAdminService.BulkAction.DELETE, List.of("u1"), options));

    assertThat(count).isEqualTo(1);
    verify(userAdminService)
        .bulk(any(), eq(UserAdminService.BulkAction.DELETE), eq(List.of("u1")), eq(options));
  }

  @Test
  void actorUnresolvedThrowsUnauthorized() {
    when(userRepository.findById("admin1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.get(authentication, "u1"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(((ApiException) e).getCode()).isEqualTo("unauthorized");
            });
    verify(userAdminService, org.mockito.Mockito.never()).getUser(any(), any());
  }
}
