package com.passagevpn.api.admin;

import com.passagevpn.monitor.MgmtClientManager;
import com.passagevpn.network.Daemon;
import com.passagevpn.network.DaemonService;
import com.passagevpn.network.DaemonService.DaemonRequest;
import com.passagevpn.profile.ProfileType;
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

/** Admin management of the OpenVPN daemons and per-profile-type daemon mapping. */
@RestController
@RequestMapping("/api/admin/daemons")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Daemons", description = "OpenVPN daemon management (admin-only)")
public class DaemonAdminController {

  private final DaemonService daemonService;
  private final MgmtClientManager mgmtClientManager;

  public DaemonAdminController(DaemonService daemonService, MgmtClientManager mgmtClientManager) {
    this.daemonService = daemonService;
    this.mgmtClientManager = mgmtClientManager;
  }

  @GetMapping
  public List<DaemonDto> list() {
    return daemonService.list().stream().map(d -> withDco(d)).toList();
  }

  /** Preview: which daemon currently serves the given profile type. */
  @GetMapping("/resolve/{profileType}")
  public DaemonDto resolve(@PathVariable ProfileType profileType) {
    var daemon = daemonService.entityForProfile(profileType);
    return withDco(daemon);
  }

  @PostMapping
  public DaemonDto create(@Valid @RequestBody DaemonRequest request) {
    var daemon = daemonService.create(request);
    return withDco(daemon);
  }

  @PutMapping("/{id}")
  public DaemonDto update(@PathVariable String id, @Valid @RequestBody DaemonRequest request) {
    var daemon = daemonService.update(id, request);
    return withDco(daemon);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    daemonService.delete(id);
  }

  @PostMapping("/{id}/enabled")
  public DaemonDto setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
    var daemon = daemonService.setEnabled(id, enabled);
    return withDco(daemon);
  }

  private DaemonDto withDco(Daemon daemon) {
    var cached = mgmtClientManager.cachedStatus(daemon.getNodeId(), daemon.getDaemonIndex());
    return DaemonDto.from(daemon, cached != null ? cached.dco() : null);
  }
}
