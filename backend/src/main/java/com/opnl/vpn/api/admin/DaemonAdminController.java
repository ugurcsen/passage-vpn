package com.opnl.vpn.api.admin;

import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.DaemonService.DaemonRequest;
import com.opnl.vpn.profile.ProfileType;
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

/** Admin management of the OpenVPN daemons and per-profile-type daemon mapping. */
@RestController
@RequestMapping("/api/admin/daemons")
@PreAuthorize("hasRole('ADMIN')")
public class DaemonAdminController {

  private final DaemonService daemonService;

  public DaemonAdminController(DaemonService daemonService) {
    this.daemonService = daemonService;
  }

  @GetMapping
  public List<DaemonDto> list() {
    return daemonService.list().stream().map(DaemonDto::from).toList();
  }

  /** Preview: which daemon currently serves the given profile type. */
  @GetMapping("/resolve/{profileType}")
  public DaemonDto resolve(@PathVariable ProfileType profileType) {
    return DaemonDto.from(daemonService.entityForProfile(profileType));
  }

  @PostMapping
  public DaemonDto create(@Valid @RequestBody DaemonRequest request) {
    return DaemonDto.from(daemonService.create(request));
  }

  @PutMapping("/{id}")
  public DaemonDto update(@PathVariable String id, @Valid @RequestBody DaemonRequest request) {
    return DaemonDto.from(daemonService.update(id, request));
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    daemonService.delete(id);
  }

  @PostMapping("/{id}/enabled")
  public DaemonDto setEnabled(
      @PathVariable String id, @RequestParam boolean enabled) {
    return DaemonDto.from(daemonService.setEnabled(id, enabled));
  }
}
