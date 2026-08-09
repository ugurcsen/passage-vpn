package com.opnl.vpn.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin group management API (admin-only). */
@RestController
@RequestMapping("/api/admin/groups")
@PreAuthorize("hasRole('ADMIN')")
public class GroupAdminController {

  private final GroupAdminService groupAdminService;

  public GroupAdminController(GroupAdminService groupAdminService) {
    this.groupAdminService = groupAdminService;
  }

  @GetMapping
  public List<GroupDto> list() {
    return groupAdminService.listGroups();
  }

  @PostMapping
  public GroupDto create(@Valid @RequestBody GroupCreateRequest request) {
    return groupAdminService.createGroup(request);
  }

  @PutMapping("/{id}")
  public GroupDto update(@PathVariable String id, @Valid @RequestBody GroupUpdateRequest request) {
    return groupAdminService.updateGroup(id, request);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    groupAdminService.deleteGroup(id);
  }

  @GetMapping("/{id}/members")
  public List<String> members(@PathVariable String id) {
    return groupAdminService.memberIds(id);
  }

  @PutMapping("/{id}/members")
  public GroupDto setMembers(
      @PathVariable String id, @Valid @RequestBody GroupMembersRequest request) {
    return groupAdminService.setMembers(id, request.userIds());
  }

  @GetMapping("/{id}/settings")
  public Map<String, Object> settings(@PathVariable String id) {
    return groupAdminService.groupSettings(id);
  }

  @PutMapping("/{id}/settings/{key}")
  public Map<String, Object> setSetting(
      @PathVariable String id, @PathVariable String key, @RequestBody Object value) {
    return groupAdminService.setGroupSetting(id, key, value);
  }

  @DeleteMapping("/{id}/settings/{key}")
  public Map<String, Object> deleteSetting(@PathVariable String id, @PathVariable String key) {
    return groupAdminService.deleteGroupSetting(id, key);
  }
}
