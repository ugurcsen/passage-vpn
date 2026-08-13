package com.opnl.vpn.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CidrUtilTest {

  @Test
  void ipv4ContainsWithinPrefix() {
    assertThat(CidrUtil.contains("10.0.0.0/8", "10.10.0.5")).isTrue();
    assertThat(CidrUtil.contains("10.0.0.0/8", "11.0.0.1")).isFalse();
  }

  @Test
  void ipv4Boundary() {
    assertThat(CidrUtil.contains("192.168.0.0/24", "192.168.0.0")).isTrue();
    assertThat(CidrUtil.contains("192.168.0.0/24", "192.168.0.255")).isTrue();
    assertThat(CidrUtil.contains("192.168.0.0/24", "192.168.1.0")).isFalse();
  }

  @Test
  void zeroAndMaxPrefix() {
    assertThat(CidrUtil.contains("0.0.0.0/0", "172.16.0.1")).isTrue();
    assertThat(CidrUtil.contains("10.10.0.5/32", "10.10.0.5")).isTrue();
    assertThat(CidrUtil.contains("10.10.0.5/32", "10.10.0.6")).isFalse();
  }

  @Test
  void ipv6ContainsWithinPrefix() {
    assertThat(CidrUtil.contains("fd00:1::/64", "fd00:1::5")).isTrue();
    assertThat(CidrUtil.contains("fd00:1::/64", "fd00:2::5")).isFalse();
    assertThat(CidrUtil.contains("2001:db8::/32", "2001:db8:1234::1")).isTrue();
  }

  @Test
  void familyMismatchReturnsFalse() {
    assertThat(CidrUtil.contains("10.0.0.0/8", "fd00:1::5")).isFalse();
    assertThat(CidrUtil.contains("fd00:1::/64", "10.10.0.5")).isFalse();
  }

  @Test
  void malformedInputReturnsFalse() {
    assertThat(CidrUtil.contains("not-a-cidr", "10.10.0.5")).isFalse();
    assertThat(CidrUtil.contains("10.0.0.0", "10.10.0.5")).isFalse();
    assertThat(CidrUtil.contains("10.0.0.0/33", "10.10.0.5")).isFalse();
    assertThat(CidrUtil.contains("10.0.0.0/abc", "10.10.0.5")).isFalse();
    assertThat(CidrUtil.contains(null, "10.10.0.5")).isFalse();
    assertThat(CidrUtil.contains("10.0.0.0/8", null)).isFalse();
    assertThat(CidrUtil.contains(" ", "10.10.0.5")).isFalse();
  }
}
