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

/** Membership link between a user and a group (join table group_members). */
@Entity
@Table(name = "group_members")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMember {

  @Embeddable
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Id implements Serializable {
    @Column(name = "group_id", length = 36)
    private String groupId;

    @Column(name = "user_id", length = 36)
    private String userId;
  }

  @EmbeddedId private Id id;

  public GroupMember(String groupId, String userId) {
    this.id = new Id(groupId, userId);
  }
}
