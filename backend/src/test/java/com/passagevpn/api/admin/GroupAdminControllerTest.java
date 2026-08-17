package com.passagevpn.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.common.ApiException;
import com.passagevpn.user.User;
import com.passagevpn.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/** Web-layer tests for the admin group API (direct controller invocation with a mocked actor). */
class GroupAdminControllerTest {

  private GroupAdminService groupAdminService;
  private UserRepository userRepository;
  private Authentication authentication;
  private GroupAdminController controller;

  @BeforeEach
  void setUp() {
    groupAdminService = mock(GroupAdminService.class);
    userRepository = mock(UserRepository.class);
    authentication = mock(Authentication.class);
    when(authentication.getPrincipal()).thenReturn("admin1");
    when(userRepository.findById("admin1")).thenReturn(Optional.of(admin()));
    controller = new GroupAdminController(groupAdminService, userRepository);
  }

  private User admin() {
    return User.builder()
        .id("admin1")
        .username("admin")
        .role(User.Role.ADMIN)
        .createdAt(Instant.now())
        .build();
  }

  private GroupDto groupDto() {
    return new GroupDto("g1", "Engineering", null, "Engineering team", 2, Instant.now());
  }

  @Test
  void listDelegatesToService() {
    when(groupAdminService.listGroups(any())).thenReturn(List.of(groupDto()));

    List<GroupDto> result = controller.list(authentication);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).name()).isEqualTo("Engineering");
    verify(groupAdminService).listGroups(any());
  }

  @Test
  void createDelegatesToService() {
    GroupCreateRequest request = new GroupCreateRequest("Engineering", null, "Engineering team");
    when(groupAdminService.createGroup(any(), any())).thenReturn(groupDto());

    GroupDto result = controller.create(authentication, request);

    assertThat(result.name()).isEqualTo("Engineering");
    verify(groupAdminService).createGroup(any(), eq(request));
  }

  @Test
  void createPropagatesNameTaken() {
    when(groupAdminService.createGroup(any(), any()))
        .thenThrow(
            ApiException.conflict("group_name_taken", "A group with this name already exists"));

    assertThatThrownBy(
            () ->
                controller.create(
                    authentication, new GroupCreateRequest("Engineering", null, null)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(((ApiException) e).getCode()).isEqualTo("group_name_taken");
            });
  }

  @Test
  void updateDelegatesToService() {
    GroupUpdateRequest request = new GroupUpdateRequest("Product", "Product org");
    when(groupAdminService.updateGroup(any(), eq("g1"), any())).thenReturn(groupDto());

    GroupDto result = controller.update(authentication, "g1", request);

    assertThat(result.name()).isEqualTo("Engineering");
    verify(groupAdminService).updateGroup(any(), eq("g1"), eq(request));
  }

  @Test
  void updatePropagatesForbidden() {
    when(groupAdminService.updateGroup(any(), eq("g9"), any()))
        .thenThrow(ApiException.forbidden("forbidden", "Group out of scope"));

    assertThatThrownBy(
            () -> controller.update(authentication, "g9", new GroupUpdateRequest("x", null)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(((ApiException) e).getCode()).isEqualTo("forbidden");
            });
  }

  @Test
  void deleteDelegatesToService() {
    controller.delete(authentication, "g1");

    verify(groupAdminService).deleteGroup(any(), eq("g1"));
  }

  @Test
  void deletePropagatesCannotDeleteRoot() {
    doThrow(
            ApiException.forbidden(
                "cannot_delete_root", "GROUP_ADMIN cannot delete the root group they manage"))
        .when(groupAdminService)
        .deleteGroup(any(), eq("g1"));

    assertThatThrownBy(() -> controller.delete(authentication, "g1"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(((ApiException) e).getCode()).isEqualTo("cannot_delete_root");
            });
  }

  @Test
  void membersDelegatesToService() {
    when(groupAdminService.memberIds(any(), eq("g1"))).thenReturn(List.of("u1", "u2"));

    List<String> result = controller.members(authentication, "g1");

    assertThat(result).containsExactly("u1", "u2");
    verify(groupAdminService).memberIds(any(), eq("g1"));
  }

  @Test
  void setMembersDelegatesToService() {
    when(groupAdminService.setMembers(any(), eq("g1"), any())).thenReturn(groupDto());

    GroupDto result =
        controller.setMembers(authentication, "g1", new GroupMembersRequest(List.of("u1")));

    assertThat(result.name()).isEqualTo("Engineering");
    verify(groupAdminService).setMembers(any(), eq("g1"), eq(List.of("u1")));
  }

  @Test
  void staticIpPoolDelegatesToService() {
    when(groupAdminService.staticIpPool(any(), eq("g1"))).thenReturn("10.8.0.100-10.8.0.199");

    String result = controller.staticIpPool(authentication, "g1");

    assertThat(result).isEqualTo("10.8.0.100-10.8.0.199");
    verify(groupAdminService).staticIpPool(any(), eq("g1"));
  }

  @Test
  void setStaticIpPoolDelegatesToService() {
    when(groupAdminService.setStaticIpPool(any(), eq("g1"), eq("10.8.0.100-10.8.0.199")))
        .thenReturn("10.8.0.100-10.8.0.199");

    String result =
        controller.setStaticIpPool(
            authentication, "g1", new StaticIpPoolRequest("10.8.0.100-10.8.0.199"));

    assertThat(result).isEqualTo("10.8.0.100-10.8.0.199");
    verify(groupAdminService).setStaticIpPool(any(), eq("g1"), eq("10.8.0.100-10.8.0.199"));
  }

  @Test
  void staticIpv6PoolDelegatesToService() {
    when(groupAdminService.staticIpv6Pool(any(), eq("g1"))).thenReturn("fd00:1::10-fd00:1::ff");

    String result = controller.staticIpv6Pool(authentication, "g1");

    assertThat(result).isEqualTo("fd00:1::10-fd00:1::ff");
    verify(groupAdminService).staticIpv6Pool(any(), eq("g1"));
  }

  @Test
  void setStaticIpv6PoolDelegatesToService() {
    when(groupAdminService.setStaticIpv6Pool(any(), eq("g1"), eq("fd00:1::10-fd00:1::ff")))
        .thenReturn("fd00:1::10-fd00:1::ff");

    String result =
        controller.setStaticIpv6Pool(
            authentication, "g1", new StaticIpPoolRequest("fd00:1::10-fd00:1::ff"));

    assertThat(result).isEqualTo("fd00:1::10-fd00:1::ff");
    verify(groupAdminService).setStaticIpv6Pool(any(), eq("g1"), eq("fd00:1::10-fd00:1::ff"));
  }

  @Test
  void settingsDelegatesToService() {
    when(groupAdminService.groupSettings(any(), eq("g1")))
        .thenReturn(Map.of("dns_servers", "1.1.1.1"));

    Map<String, Object> result = controller.settings(authentication, "g1");

    assertThat(result).containsEntry("dns_servers", "1.1.1.1");
    verify(groupAdminService).groupSettings(any(), eq("g1"));
  }

  @Test
  void setSettingDelegatesToService() {
    when(groupAdminService.setGroupSetting(any(), eq("g1"), eq("dns_servers"), eq("1.1.1.1")))
        .thenReturn(Map.of("dns_servers", "1.1.1.1"));

    Map<String, Object> result =
        controller.setSetting(authentication, "g1", "dns_servers", "1.1.1.1");

    assertThat(result).containsEntry("dns_servers", "1.1.1.1");
    verify(groupAdminService).setGroupSetting(any(), eq("g1"), eq("dns_servers"), eq("1.1.1.1"));
  }

  @Test
  void deleteSettingDelegatesToService() {
    when(groupAdminService.deleteGroupSetting(any(), eq("g1"), eq("dns_servers")))
        .thenReturn(Map.of());

    Map<String, Object> result = controller.deleteSetting(authentication, "g1", "dns_servers");

    assertThat(result).isEmpty();
    verify(groupAdminService).deleteGroupSetting(any(), eq("g1"), eq("dns_servers"));
  }

  @Test
  void memberIdsPropagatesGroupNotFound() {
    when(groupAdminService.memberIds(any(), eq("ghost")))
        .thenThrow(ApiException.notFound("group_not_found", "Group not found"));

    assertThatThrownBy(() -> controller.members(authentication, "ghost"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(((ApiException) e).getCode()).isEqualTo("group_not_found");
            });
  }

  @Test
  void actorUnresolvedThrowsUnauthorized() {
    when(userRepository.findById("admin1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.list(authentication))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(((ApiException) e).getCode()).isEqualTo("unauthorized");
            });
    verify(groupAdminService, org.mockito.Mockito.never()).listGroups(any());
  }
}
