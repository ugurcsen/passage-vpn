package com.opnl.vpn.api.admin;

import com.opnl.vpn.dns.DnsOverrideService;
import com.opnl.vpn.dns.DnsRecordDto;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

/** DNS override management (internal hostname → IPv4 records for VPN clients). */
@RestController
@RequestMapping("/api/admin/dns-overrides")
@PreAuthorize("hasRole('ADMIN')")
@Tag(
    name = "Admin - DNS Overrides",
    description = "Internal hostname -> IPv4 records served by the VPN resolver")
public class DnsRecordAdminController {

  private final DnsOverrideService dnsOverrideService;

  public DnsRecordAdminController(DnsOverrideService dnsOverrideService) {
    this.dnsOverrideService = dnsOverrideService;
  }

  @GetMapping
  @Operation(summary = "List DNS overrides")
  public List<DnsRecordDto> list() {
    return dnsOverrideService.list();
  }

  @PostMapping
  @Operation(summary = "Create a DNS override")
  public DnsRecordDto create(@Valid @RequestBody DnsRecordDto dto) {
    return dnsOverrideService.create(dto);
  }

  @PutMapping("/{id}")
  @Operation(summary = "Update a DNS override")
  public DnsRecordDto update(@PathVariable String id, @Valid @RequestBody DnsRecordDto dto) {
    return dnsOverrideService.update(id, dto);
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete a DNS override")
  public void delete(@PathVariable String id) {
    dnsOverrideService.delete(id);
  }

  @PostMapping("/{id}/enabled")
  @Operation(summary = "Enable or disable a DNS override")
  public DnsRecordDto setEnabled(@PathVariable String id, @RequestBody Boolean enabled) {
    return dnsOverrideService.setEnabled(id, enabled != null && enabled);
  }
}
