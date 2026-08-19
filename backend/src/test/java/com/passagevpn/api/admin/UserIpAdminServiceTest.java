package com.passagevpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.audit.AuditLogService;
import com.passagevpn.ccd.CcdService;
import com.passagevpn.common.ApiException;
import com.passagevpn.group.GroupScope;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserIpAdminServiceTest {

  private UserRepository userRepository;
  private GroupScope groupScope;
  private CcdService ccdService;
  private UserAdminService userAdminService;
  private UserIpAdminService service;

  private User admin() {
    return User.builder()
        .id("admin1")
        .username("admin")
        .role(User.Role.ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  private User bob() {
    return User.builder()
        .id("u2")
        .username("bob")
        .role(User.Role.USER)
        .createdAt(Instant.now())
        .build();
  }

  private UserDto bobDto() {
    return UserDto.from(bob(), false, false, List.of(), List.of(), List.of());
  }

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    groupScope = mock(GroupScope.class);
    ccdService = mock(CcdService.class);
    userAdminService = mock(UserAdminService.class);
    service =
        new UserIpAdminService(
            userRepository, groupScope, ccdService, mock(AuditLogService.class), userAdminService);
    when(userRepository.findById("u2")).thenReturn(Optional.of(bob()));
    when(userRepository.existsById("u2")).thenReturn(true);
    when(userAdminService.getUser(any(), eq("u2"))).thenReturn(bobDto());
  }

  @Test
  void setStaticIpDelegatesToCcd() {
    service.setStaticIp(admin(), "u2", "10.8.0.100");
    verify(ccdService).setStaticIp("u2", "10.8.0.100");
  }

  @Test
  void allocateStaticIpDelegatesToCcd() {
    service.allocateStaticIp(admin(), "u2");
    verify(ccdService).allocateFromGroupPool("u2");
  }

  @Test
  void clearStaticIpDelegatesToCcd() {
    service.clearStaticIp(admin(), "u2");
    verify(ccdService).clearStaticIp("u2");
  }

  @Test
  void setStaticIpv6DelegatesToCcd() {
    service.setStaticIpv6(admin(), "u2", "fd00:1::10");
    verify(ccdService).setStaticIpv6("u2", "fd00:1::10");
  }

  @Test
  void allocateStaticIpv6DelegatesToCcd() {
    service.allocateStaticIpv6(admin(), "u2");
    verify(ccdService).allocateIpv6FromGroupPool("u2");
  }

  @Test
  void clearStaticIpv6DelegatesToCcd() {
    service.clearStaticIpv6(admin(), "u2");
    verify(ccdService).clearStaticIpv6("u2");
  }

  @Test
  void groupAdminCannotManageAdminAccount() {
    User groupAdmin =
        User.builder()
            .id("gadmin1")
            .username("gadmin")
            .role(User.Role.GROUP_ADMIN)
            .createdAt(Instant.now())
            .build();
    when(userRepository.findById("gadmin1")).thenReturn(Optional.of(groupAdmin));
    when(userRepository.existsById("gadmin1")).thenReturn(true);

    assertThatThrownBy(() -> service.setStaticIp(groupAdmin, "gadmin1", "10.8.0.200"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("forbidden"));
  }

  @Test
  void throwsWhenUserNotFound() {
    when(userRepository.existsById("nonexistent")).thenReturn(false);

    assertThatThrownBy(() -> service.setStaticIp(admin(), "nonexistent", "10.8.0.100"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("user_not_found"));
  }
}
