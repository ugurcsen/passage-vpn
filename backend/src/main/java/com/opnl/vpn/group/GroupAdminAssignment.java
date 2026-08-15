package com.opnl.vpn.group;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Binds a GROUP_ADMIN user to a root group they manage. The role's scope covers the assigned root
 * group and its entire subtree of subgroups. Join table {@code group_admin_assignments}.
 */
@Entity
@Table(name = "group_admin_assignments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupAdminAssignment {

  @Embeddable
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Id implements Serializable {
    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "group_id", length = 36)
    private String groupId;
  }

  @EmbeddedId private Id id;

  public GroupAdminAssignment(String userId, String groupId) {
    this.id = new Id(userId, groupId);
  }
}
