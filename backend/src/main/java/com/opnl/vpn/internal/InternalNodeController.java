package com.opnl.vpn.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.opnl.vpn.network.NodeRegistryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Node-facing endpoints called by remote agent instances (Spring profile {@code agent}) over the
 * restricted docker network. The agent registers itself once (idempotent by name) and then sends
 * periodic heartbeats so the central backend knows the gateway is online and where to reach its
 * management sockets. Protected by the shared {@code X-Internal-Token} like the other /internal
 * endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/internal/node")
@Tag(name = "Node agent", description = "Endpoints used by remote node agents (restricted network)")
public class InternalNodeController {

  private final NodeRegistryService nodeRegistryService;

  public InternalNodeController(NodeRegistryService nodeRegistryService) {
    this.nodeRegistryService = nodeRegistryService;
  }

  /** Registers (or re-registers) the calling agent's gateway node and returns its id. */
  @PostMapping("/register")
  public RegisterResult register(@RequestBody RegisterRequest request) {
    String nodeId =
        nodeRegistryService.upsertByAgent(
            request.name(), request.mgmtHost(), request.mgmtPortBase(), request.adminIp());
    log.info("Node agent registered node '{}' ({})", request.name(), nodeId);
    return new RegisterResult(nodeId);
  }

  /** Refreshes the node's heartbeat so the central backend keeps reporting it online. */
  @PostMapping("/heartbeat")
  public void heartbeat(@RequestBody HeartbeatRequest request) {
    nodeRegistryService.heartbeat(request.nodeId());
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RegisterRequest(String name, String mgmtHost, int mgmtPortBase, String adminIp) {}

  public record HeartbeatRequest(String nodeId) {}

  public record RegisterResult(String nodeId) {}
}
