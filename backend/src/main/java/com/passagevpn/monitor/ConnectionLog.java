package com.passagevpn.monitor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted record of one VPN session (start/finish times, endpoints, transferred bytes). Written
 * by the internal connect/disconnect callbacks and pruned after the retention window.
 */
@Entity
@Table(
    name = "connection_logs",
    indexes = {
      @Index(name = "idx_connection_logs_connected_at", columnList = "connected_at"),
      @Index(name = "idx_connection_logs_username", columnList = "username"),
      @Index(name = "idx_connection_logs_disconnected_at", columnList = "disconnected_at")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionLog {

  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false)
  private String username;

  @Column(name = "common_name", nullable = false)
  private String commonName;

  @Column(name = "virtual_ip")
  private String virtualIp;

  @Column(name = "remote_ip")
  private String remoteIp;

  @Column(name = "daemon_name")
  private String daemonName;

  @Column(name = "node_id")
  private String nodeId;

  @Column(name = "connected_at", nullable = false)
  private Instant connectedAt;

  @Column(name = "disconnected_at")
  private Instant disconnectedAt;

  @Column(name = "bytes_in", nullable = false)
  @Builder.Default
  private long bytesIn = 0;

  @Column(name = "bytes_out", nullable = false)
  @Builder.Default
  private long bytesOut = 0;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
