package com.opnl.vpn.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Per-group setting. Values stored as JSON strings (portable to PostgreSQL). */
@Entity
@Table(name = "group_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupSetting {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "group_id", nullable = false, length = 36)
  private String groupId;

  @Column(name = "setting_key", nullable = false, length = 64)
  private String key;

  @Column(name = "setting_value", columnDefinition = "TEXT")
  private String value;
}
