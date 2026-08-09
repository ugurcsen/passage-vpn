package com.opnl.vpn.network;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Server-level setting. Values are stored as JSON strings (portable to PostgreSQL). */
@Entity
@Table(name = "server_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerSetting {

  @Id
  @Column(name = "setting_key", length = 64)
  private String key;

  @Column(name = "setting_value", columnDefinition = "TEXT")
  private String value;
}
