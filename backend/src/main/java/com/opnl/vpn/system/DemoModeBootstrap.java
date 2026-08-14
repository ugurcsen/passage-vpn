package com.opnl.vpn.system;

import com.opnl.vpn.setup.SetupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Auto-seeds the demo dataset on first boot when {@code OPNL_DEMO_MODE=true}. Runs once after the
 * setup wizard is complete; a {@code demo.seeded} marker keeps later restarts no-ops unless the
 * data is wiped and {@code make seed-demo} is invoked manually.
 */
@Slf4j
@Component
public class DemoModeBootstrap implements ApplicationRunner {

  private final boolean enabled;
  private final DemoSeedService demoSeedService;
  private final SetupService setupService;

  public DemoModeBootstrap(
      @Value("${opnl.demo-mode:false}") boolean enabled,
      DemoSeedService demoSeedService,
      SetupService setupService) {
    this.enabled = enabled;
    this.demoSeedService = demoSeedService;
    this.setupService = setupService;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }
    try {
      if (!setupService.complete()) {
        log.info(
            "Demo mode enabled but setup is not complete; run 'make seed-demo' after finishing the wizard");
        return;
      }
      if (demoSeedService.seeded()) {
        log.info("Demo mode enabled but demo data is already loaded; skipping");
        return;
      }
      int users = demoSeedService.seed(false);
      log.info("Demo mode: seeded {} sample users", users);
    } catch (Exception e) {
      log.warn("Demo seeding skipped: {}", e.getMessage());
    }
  }
}
