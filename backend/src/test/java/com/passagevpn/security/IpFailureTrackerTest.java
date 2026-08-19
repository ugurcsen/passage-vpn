package com.passagevpn.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.passagevpn.config.PassageProperties;
import org.junit.jupiter.api.Test;

class IpFailureTrackerTest {

  private IpFailureTracker tracker(int maxFailures, int windowSeconds, int blockSeconds) {
    PassageProperties.Auth auth =
        new PassageProperties.Auth("local", maxFailures, windowSeconds, blockSeconds, 30, 60, null);
    PassageProperties.OpenVpn openvpn =
        new PassageProperties.OpenVpn(
            "127.0.0.1",
            7505,
            "vpn.example.com",
            "/pki",
            "/ccd",
            "/config",
            "/scripts",
            "/scripts",
            "http://localhost",
            "easyrsa",
            "/logs",
            "mgmt-secret",
            730,
            1194,
            1194,
            1195,
            1195);
    PassageProperties properties =
        new PassageProperties(
            "./data",
            "OpenVPN Panel",
            "internal-token",
            new PassageProperties.Jwt("secret-secret-secret", 300, 14),
            auth,
            openvpn);
    return new IpFailureTracker(properties);
  }

  @Test
  void isNotBlockedForUnknownOrBlankIp() {
    IpFailureTracker tracker = tracker(3, 300, 300);
    assertThat(tracker.isBlocked("1.2.3.4")).isFalse();
    assertThat(tracker.isBlocked(null)).isFalse();
    assertThat(tracker.isBlocked("   ")).isFalse();
  }

  @Test
  void ignoresBlankIpsForAllOperations() {
    IpFailureTracker tracker = tracker(1, 300, 300);
    tracker.recordFailure(null);
    tracker.recordFailure(" ");
    tracker.reset(null);
    tracker.reset("  ");
    assertThat(tracker.isBlocked("10.0.0.1")).isFalse();
  }

  @Test
  void blocksIpOnceFailuresReachThresholdWithinWindow() {
    IpFailureTracker tracker = tracker(3, 300, 300);
    tracker.recordFailure("5.6.7.8");
    tracker.recordFailure("5.6.7.8");
    assertThat(tracker.isBlocked("5.6.7.8")).isFalse();
    tracker.recordFailure("5.6.7.8");
    assertThat(tracker.isBlocked("5.6.7.8")).isTrue();
  }

  @Test
  void blocksOnSecondFailureWhenThresholdIsOne() {
    IpFailureTracker tracker = tracker(1, 300, 300);
    tracker.recordFailure("9.9.9.9");
    assertThat(tracker.isBlocked("9.9.9.9")).isFalse();
    tracker.recordFailure("9.9.9.9");
    assertThat(tracker.isBlocked("9.9.9.9")).isTrue();
  }

  @Test
  void resetClearsFailuresForIp() {
    IpFailureTracker tracker = tracker(3, 300, 300);
    tracker.recordFailure("5.6.7.8");
    tracker.recordFailure("5.6.7.8");
    tracker.reset("5.6.7.8");
    tracker.recordFailure("5.6.7.8");
    tracker.recordFailure("5.6.7.8");
    assertThat(tracker.isBlocked("5.6.7.8")).isFalse();
  }

  @Test
  void failuresAreTrackedPerIp() {
    IpFailureTracker tracker = tracker(2, 300, 300);
    tracker.recordFailure("1.1.1.1");
    tracker.recordFailure("1.1.1.1");
    assertThat(tracker.isBlocked("1.1.1.1")).isTrue();
    assertThat(tracker.isBlocked("2.2.2.2")).isFalse();
  }

  @Test
  void blockExpiresAfterBlockDuration() throws InterruptedException {
    IpFailureTracker tracker = tracker(2, 300, 1);
    tracker.recordFailure("5.6.7.8");
    tracker.recordFailure("5.6.7.8");
    assertThat(tracker.isBlocked("5.6.7.8")).isTrue();

    Thread.sleep(1700);

    assertThat(tracker.isBlocked("5.6.7.8")).isFalse();
  }

  @Test
  void failureWindowExpiryRestartsCounting() throws InterruptedException {
    IpFailureTracker tracker = tracker(3, 1, 300);
    tracker.recordFailure("5.6.7.8");
    tracker.recordFailure("5.6.7.8");
    assertThat(tracker.isBlocked("5.6.7.8")).isFalse();

    Thread.sleep(2300);

    tracker.recordFailure("5.6.7.8");
    tracker.recordFailure("5.6.7.8");
    assertThat(tracker.isBlocked("5.6.7.8")).isFalse();
    tracker.recordFailure("5.6.7.8");
    assertThat(tracker.isBlocked("5.6.7.8")).isTrue();
  }
}
