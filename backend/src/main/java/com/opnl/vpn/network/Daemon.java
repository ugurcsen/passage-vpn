package com.opnl.vpn.network;

import com.opnl.vpn.network.ServerConfig.Protocol;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A configurable OpenVPN daemon. Daemon index 0 is the primary daemon created by the setup wizard.
 */
@Entity
@Table(name = "daemons")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Daemon {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "daemon_index", nullable = false)
  private int daemonIndex;

  @Column(name = "name")
  private String name;

  @Column(nullable = false)
  private int port;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private Protocol proto;

  @Column(nullable = false)
  private String subnet;

  @Column(name = "subnet_mask", nullable = false)
  private String subnetMask;

  @Convert(converter = JsonListConverter.class)
  @Column(name = "dns_servers")
  @Builder.Default
  private List<String> dnsServers = new ArrayList<>();

  @Column(name = "domain")
  private String domain;

  @Convert(converter = JsonListConverter.class)
  @Column(name = "extra_routes")
  @Builder.Default
  private List<String> extraRoutes = new ArrayList<>();

  @Column(name = "full_tunnel", nullable = false)
  @Builder.Default
  private boolean fullTunnel = true;

  @Column(name = "client_cert_not_required", nullable = false)
  @Builder.Default
  private boolean clientCertNotRequired = false;

  @Column(name = "auth_user_pass", nullable = false)
  @Builder.Default
  private boolean authUserPass = true;

  @Column(name = "admin_host")
  private String adminHost;

  @Column(name = "ipv6_enabled", nullable = false)
  @Builder.Default
  private boolean ipv6Enabled = false;

  @Column(name = "ipv6_subnet")
  private String ipv6Subnet;

  @Column(nullable = false)
  @Builder.Default
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
