package com.opnl.vpn.network;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A registered VPN gateway node. The local deployment is implicit (never stored); remote nodes
 * register here so the central backend can route status/kill/monitor requests per node and accept
 * agent heartbeats from remote agents.
 */
@Entity
@Table(name = "openvpn_nodes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenVpnNode {

  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, unique = true)
  private String name;

  @Column(name = "mgmt_host", nullable = false)
  private String mgmtHost;

  @Column(name = "mgmt_port_base", nullable = false)
  @Builder.Default
  private int mgmtPortBase = 7505;

  @Column(name = "admin_ip")
  private String adminIp;

  @Column(nullable = false)
  @Builder.Default
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_seen_at")
  private Instant lastSeenAt;
}
