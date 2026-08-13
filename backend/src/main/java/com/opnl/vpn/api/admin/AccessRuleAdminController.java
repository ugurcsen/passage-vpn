package com.opnl.vpn.api.admin;

import com.opnl.vpn.access.AccessRuleDto;
import com.opnl.vpn.access.AccessRuleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin access-rule management: per-network firewalling for users and groups. */
@RestController
@RequestMapping("/api/admin/rules")
@PreAuthorize("hasRole('ADMIN')")
@Tag(
    name = "Admin - Access Rules",
    description = "Per-network access rules for users and groups (admin-only)")
public class AccessRuleAdminController {

  private final AccessRuleService ruleService;

  public AccessRuleAdminController(AccessRuleService ruleService) {
    this.ruleService = ruleService;
  }

  @GetMapping
  public List<AccessRuleDto> list() {
    return ruleService.list();
  }

  @PostMapping
  public AccessRuleDto create(@Valid @RequestBody AccessRuleDto dto) {
    return ruleService.create(dto);
  }

  @PutMapping("/{id}")
  public AccessRuleDto update(@PathVariable String id, @Valid @RequestBody AccessRuleDto dto) {
    return ruleService.update(id, dto);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    ruleService.delete(id);
  }

  @PostMapping("/{id}/enabled")
  public AccessRuleDto setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
    return ruleService.setEnabled(id, enabled);
  }
}
