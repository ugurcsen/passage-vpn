package com.opnl.vpn.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ConnectionRegistryTest {

  @Test
  void retainOnlyKeepsSessionsPresentInLiveView() {
    ConnectionRegistry registry = new ConnectionRegistry();
    registry.register("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");
    registry.register("bob", "bob", "10.8.0.3", "203.0.113.9", "daemon-1");

    registry.retainOnly(Set.of("alice"));

    assertThat(registry.sessions()).hasSize(1);
    assertThat(registry.sessions().get(0).commonName()).isEqualTo("alice");
    assertThat(registry.byVirtualIp("10.8.0.2")).isPresent();
    assertThat(registry.byVirtualIp("10.8.0.3")).isEmpty();
  }

  @Test
  void retainOnlyWithEmptyLiveViewClearsEverything() {
    ConnectionRegistry registry = new ConnectionRegistry();
    registry.register("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");

    registry.retainOnly(Set.of());

    assertThat(registry.sessions()).isEmpty();
  }

  @Test
  void retainOnlyWithNullLiveViewIsNoop() {
    ConnectionRegistry registry = new ConnectionRegistry();
    registry.register("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");

    registry.retainOnly(null);

    assertThat(registry.sessions()).hasSize(1);
  }

  @Test
  void learnDeleteDropsSessionAndVirtualIpMapping() {
    ConnectionRegistry registry = new ConnectionRegistry();
    registry.register("alice", "alice", "10.8.0.2", "203.0.113.5", "daemon-0");

    registry.learn("delete", "10.8.0.2", "alice");

    assertThat(registry.sessions()).isEmpty();
    assertThat(registry.byVirtualIp("10.8.0.2")).isEmpty();
  }
}
