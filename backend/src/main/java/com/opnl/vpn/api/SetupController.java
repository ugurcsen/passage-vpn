package com.opnl.vpn.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.opnl.vpn.network.ServerConfig;
import com.opnl.vpn.setup.SetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** First-run setup wizard endpoints (public until setup completes). */
@RestController
@RequestMapping("/api/setup")
@Tag(name = "Setup", description = "First-run configuration wizard")
public class SetupController {

  private final SetupService setupService;

  public SetupController(SetupService setupService) {
    this.setupService = setupService;
  }

  @GetMapping("/state")
  @Operation(summary = "Current setup state")
  public SetupService.SetupStatus state() {
    return setupService.status();
  }

  @PostMapping("/wizard")
  @Operation(summary = "Execute one wizard step")
  public SetupService.SetupStatus wizard(@RequestBody SetupWizardRequest request) {
    setupService.runStep(request.step(), request.payload());
    return setupService.status();
  }

  @GetMapping("/server-config")
  @Operation(summary = "Current network/server configuration")
  public ServerConfig serverConfig() {
    return setupService.currentServerConfig();
  }

  public record SetupWizardRequest(String step, JsonNode payload) {}
}
