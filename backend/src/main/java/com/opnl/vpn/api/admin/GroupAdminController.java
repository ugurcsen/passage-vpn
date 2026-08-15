package com.opnl.vpn.api.admin;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.user.User;
import com.opnl.vpn.user.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin group management API (ADMIN and scoped GROUP_ADMIN). */
@RestController
@RequestMapping("/api/admin/groups")
@PreAuthorize("hasAnyRole('ADMIN', 'GROUP_ADMIN')")
@Tag(name = "Admin - Groups", description = "Group management (ADMIN/GROUP_ADMIN)")
public class GroupAdminController {

  private final GroupAdminService groupAdminService;
  private final UserRepository userRepository;

  public GroupAdminController(GroupAdminService groupAdminService, UserRepository userRepository) {
    this.groupAdminService = groupAdminService;
    this.userRepository = userRepository;
  }

  @GetMapping
  public List<GroupDto> list(Authentication authentication) {
    return groupAdminService.listGroups(actor(authentication));
  }

  @PostMapping
  public GroupDto create(
      Authentication authentication, @Valid @RequestBody GroupCreateRequest request) {
    return groupAdminService.createGroup(actor(authentication), request);
  }

  @PutMapping("/{id}")
  public GroupDto update(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody GroupUpdateRequest request) {
    return groupAdminService.updateGroup(actor(authentication), id, request);
  }

  @DeleteMapping("/{id}")
  public void delete(Authentication authentication, @PathVariable String id) {
    groupAdminService.deleteGroup(actor(authentication), id);
  }

  @GetMapping("/{id}/members")
  public List<String> members(Authentication authentication, @PathVariable String id) {
    return groupAdminService.memberIds(actor(authentication), id);
  }

  @GetMapping("/{id}/static-ip-pool")
  public String staticIpPool(Authentication authentication, @PathVariable String id) {
    return groupAdminService.staticIpPool(actor(authentication), id);
  }

  @PutMapping("/{id}/static-ip-pool")
  public String setStaticIpPool(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody StaticIpPoolRequest request) {
    return groupAdminService.setStaticIpPool(actor(authentication), id, request.pool());
  }

  @GetMapping("/{id}/static-ipv6-pool")
  public String staticIpv6Pool(Authentication authentication, @PathVariable String id) {
    return groupAdminService.staticIpv6Pool(actor(authentication), id);
  }

  @PutMapping("/{id}/static-ipv6-pool")
  public String setStaticIpv6Pool(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody StaticIpPoolRequest request) {
    return groupAdminService.setStaticIpv6Pool(actor(authentication), id, request.pool());
  }

  @PutMapping("/{id}/members")
  public GroupDto setMembers(
      Authentication authentication,
      @PathVariable String id,
      @Valid @RequestBody GroupMembersRequest request) {
    return groupAdminService.setMembers(actor(authentication), id, request.userIds());
  }

  @GetMapping("/{id}/settings")
  public Map<String, Object> settings(Authentication authentication, @PathVariable String id) {
    return groupAdminService.groupSettings(actor(authentication), id);
  }

  @PutMapping("/{id}/settings/{key}")
  public Map<String, Object> setSetting(
      Authentication authentication,
      @PathVariable String id,
      @PathVariable String key,
      @RequestBody Object value) {
    return groupAdminService.setGroupSetting(actor(authentication), id, key, value);
  }

  @DeleteMapping("/{id}/settings/{key}")
  public Map<String, Object> deleteSetting(
      Authentication authentication, @PathVariable String id, @PathVariable String key) {
    return groupAdminService.deleteGroupSetting(actor(authentication), id, key);
  }

  private User actor(Authentication authentication) {
    return userRepository
        .findById(authentication.getPrincipal().toString())
        .orElseThrow(() -> ApiException.unauthorized("unauthorized", "Authentication required"));
  }
}
