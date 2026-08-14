package com.opnl.vpn.api.admin;

import com.opnl.vpn.network.OpenVpnNode;
import java.time.Instant;

/** Public view of a registered VPN gateway node. */
public record OpenVpnNodeDto(
    String id,
    String name,
    String mgmtHost,
    int mgmtPortBase,
    String adminIp,
    boolean mgmtPasswordSet,
    String lastSeenIp,
    boolean enabled,
    Instant createdAt,
    Instant lastSeenAt,
    boolean online) {

  /** A node counts as online when its agent heartbeat is fresh. */
  public static final long ONLINE_WINDOW_SECONDS = 30;

  public static OpenVpnNodeDto from(OpenVpnNode node, Instant now) {
    boolean online =
        node.getLastSeenAt() != null
            && now.getEpochSecond() - node.getLastSeenAt().getEpochSecond()
                <= ONLINE_WINDOW_SECONDS;
    return new OpenVpnNodeDto(
        node.getId(),
        node.getName(),
        node.getMgmtHost(),
        node.getMgmtPortBase(),
        node.getAdminIp(),
        node.getMgmtPassword() != null && !node.getMgmtPassword().isBlank(),
        node.getLastSeenIp(),
        node.isEnabled(),
        node.getCreatedAt(),
        node.getLastSeenAt(),
        online);
  }
}
