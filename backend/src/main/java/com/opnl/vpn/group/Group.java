package com.opnl.vpn.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A user group. Groups can be nested and carry inherited settings. */
@Entity
@Table(name = "user_groups")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Group {

  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, unique = true, length = 64)
  private String name;

  @Column(name = "parent_id", length = 36)
  private String parentId;

  @Column(length = 256)
  private String description;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
