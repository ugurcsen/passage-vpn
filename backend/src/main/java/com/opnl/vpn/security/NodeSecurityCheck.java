package com.opnl.vpn.security;

import com.opnl.vpn.network.OpenVpnNodeRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Warns at startup about registered remote nodes that still lack a management password. Such nodes
 * cannot be monitored (management connections fail closed), so the operator must set a password via
 * the node update API before the node can be routed.
 */
@Slf4j
@Component
public class NodeSecurityCheck implements ApplicationRunner {

  private final OpenVpnNodeRepository nodeRepository;

  public NodeSecurityCheck(OpenVpnNodeRepository nodeRepository) {
    this.nodeRepository = nodeRepository;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> missing =
        nodeRepository.findAllByOrderByCreatedAtAsc().stream()
            .filter(node -> node.getMgmtPassword() == null || node.getMgmtPassword().isBlank())
            .map(node -> node.getName())
            .toList();
    if (!missing.isEmpty()) {
      log.warn(
          "The following registered nodes have no management password set and cannot be "
              + "monitored: {}. Set one via the node update API before enabling them.",
          String.join(", ", missing));
    }
  }
}
