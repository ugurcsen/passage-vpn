package com.passagevpn.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Key/value metadata store (setup state, schema markers, runtime flags). */
@Entity
@Table(name = "app_meta")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppMeta {

  @Id
  @Column(name = "meta_key", length = 64)
  private String key;

  @Column(name = "meta_value", columnDefinition = "TEXT")
  private String value;

  public static AppMeta of(String key, String value) {
    return AppMeta.builder().key(key).value(value).build();
  }
}
