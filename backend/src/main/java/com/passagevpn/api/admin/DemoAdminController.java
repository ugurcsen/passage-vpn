package com.passagevpn.api.admin;

import com.passagevpn.system.DemoSeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-facing demo data seeding (mirrors the {@code make seed-demo} internal endpoint). */
@RestController
@RequestMapping("/api/admin/demo")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Demo", description = "Demo data seeding (admin-only)")
public class DemoAdminController {

  private final DemoSeedService demoSeedService;

  public DemoAdminController(DemoSeedService demoSeedService) {
    this.demoSeedService = demoSeedService;
  }

  /**
   * Loads sample users, groups, access rules, DNS overrides, certificate rows and connection
   * history. Returns 409 when demo data is already loaded unless {@code force} is set.
   */
  @PostMapping("/seed")
  @Operation(
      summary = "Load demo data",
      description =
          "Seeds sample users, groups, access rules, DNS overrides, certificate rows and connection history")
  public SeedDemoResponse seed(@RequestBody(required = false) SeedDemoRequest request) {
    int users = demoSeedService.seed(request != null && request.force());
    return new SeedDemoResponse(users);
  }

  public record SeedDemoRequest(boolean force) {}

  public record SeedDemoResponse(int users) {}
}
