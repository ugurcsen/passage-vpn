package com.passagevpn.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class Ipv6UtilTest {

  // ---- canonicalIpv6 ----

  @Test
  void canonicalIpv6_loopback() {
    byte[] addr = new byte[16];
    addr[15] = 1;
    assertThat(Ipv6Util.canonicalIpv6(addr)).isEqualTo("::1");
  }

  @Test
  void canonicalIpv6_allZeros() {
    assertThat(Ipv6Util.canonicalIpv6(new byte[16])).isEqualTo("::");
  }

  @Test
  void canonicalIpv6_fullExpansion() {
    byte[] addr = {
      (byte) 0x20,
      0x01,
      0x0d,
      (byte) 0xb8,
      (byte) 0x85,
      (byte) 0xa3,
      0x00,
      0x00,
      0x00,
      0x00,
      (byte) 0x8a,
      0x2e,
      0x03,
      0x70,
      (byte) 0x73,
      (byte) 0x34
    };
    assertThat(Ipv6Util.canonicalIpv6(addr)).isEqualTo("2001:db8:85a3::8a2e:370:7334");
  }

  @Test
  void canonicalIpv6_longestZeroRun() {
    byte[] addr = {
      (byte) 0xfd,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x01
    };
    assertThat(Ipv6Util.canonicalIpv6(addr)).isEqualTo("fd00::1");
  }

  @Test
  void canonicalIpv6_longestRunWins() {
    // 2001:0:0:1:0:0:0:1 — run at groups 4-6 (len 3) beats run at groups 1-2 (len 2)
    byte[] addr = {
      0x20, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01
    };
    assertThat(Ipv6Util.canonicalIpv6(addr)).isEqualTo("2001:0:0:1::1");
  }

  @Test
  void canonicalIpv6_ipv4Mapped() {
    // ::ffff:192.168.1.1 = ::ffff:c0a8:0101
    byte[] addr = {
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      (byte) 0xff,
      (byte) 0xff,
      (byte) 0xc0,
      (byte) 0xa8,
      0x01,
      0x01
    };
    assertThat(Ipv6Util.canonicalIpv6(addr)).isEqualTo("::ffff:c0a8:101");
  }

  @Test
  void canonicalIpv6_nullThrows() {
    assertThatThrownBy(() -> Ipv6Util.canonicalIpv6(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonicalIpv6_wrongLengthThrows() {
    assertThatThrownBy(() -> Ipv6Util.canonicalIpv6(new byte[4]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- prefixMask ----

  @Test
  void prefixMask_128() {
    assertThat(Ipv6Util.prefixMask(128))
        .isEqualTo(new BigInteger("ffffffffffffffffffffffffffffffff", 16));
  }

  @Test
  void prefixMask_0() {
    assertThat(Ipv6Util.prefixMask(0)).isEqualTo(BigInteger.ZERO);
  }

  @Test
  void prefixMask_64() {
    BigInteger mask = Ipv6Util.prefixMask(64);
    assertThat(mask.toString(16)).startsWith("ffffffffffffffff");
    assertThat(mask.toString(16)).endsWith("0000000000000000");
  }

  @Test
  void prefixMask_invalidThrows() {
    assertThatThrownBy(() -> Ipv6Util.prefixMask(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ipv6Util.prefixMask(129)).isInstanceOf(IllegalArgumentException.class);
  }

  // ---- maskToNetwork ----

  @Test
  void maskToNetwork_fd00_64() {
    byte[] addr = new byte[16];
    addr[0] = (byte) 0xfd;
    addr[1] = 0x00;
    addr[15] = 0x01;
    byte[] masked = Ipv6Util.maskToNetwork(addr, 64);
    assertThat(masked[0]).isEqualTo((byte) 0xfd);
    assertThat(masked[1]).isEqualTo((byte) 0x00);
    // host bits zeroed
    for (int i = 8; i < 16; i++) {
      assertThat(masked[i]).isEqualTo((byte) 0);
    }
  }

  @Test
  void maskToNetwork_nullThrows() {
    assertThatThrownBy(() -> Ipv6Util.maskToNetwork(null, 64))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- toUnsigned ----

  @Test
  void toUnsigned_singleByte() {
    assertThat(Ipv6Util.toUnsigned(new byte[] {(byte) 0xff})).isEqualTo(BigInteger.valueOf(255));
  }

  @Test
  void toUnsigned_empty() {
    assertThat(Ipv6Util.toUnsigned(new byte[0])).isEqualTo(BigInteger.ZERO);
  }

  @Test
  void toUnsigned_nullThrows() {
    assertThatThrownBy(() -> Ipv6Util.toUnsigned(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- parseIpv6 ----

  @Test
  void parseIpv6_loopback() {
    assertThat(Ipv6Util.parseIpv6("::1")).isEqualTo(BigInteger.ONE);
  }

  @Test
  void parseIpv6_fd00_1() {
    // fd00::1 = 0xfd000000000000000000000000000001
    BigInteger expected = new BigInteger("fd000000000000000000000000000001", 16);
    assertThat(Ipv6Util.parseIpv6("fd00::1")).isEqualTo(expected);
  }

  @Test
  void parseIpv6_blankThrows() {
    assertThatThrownBy(() -> Ipv6Util.parseIpv6("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ipv6Util.parseIpv6(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parseIpv6_ipv4Throws() {
    assertThatThrownBy(() -> Ipv6Util.parseIpv6("192.168.1.1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- formatIpv6 ----

  @Test
  void formatIpv6_zero() {
    assertThat(Ipv6Util.formatIpv6(BigInteger.ZERO)).isEqualTo("::");
  }

  @Test
  void formatIpv6_one() {
    assertThat(Ipv6Util.formatIpv6(BigInteger.ONE)).isEqualTo("::1");
  }

  @Test
  void formatIpv6_fd00_1() {
    BigInteger value = new BigInteger("fd000000000000000000000000000001", 16);
    assertThat(Ipv6Util.formatIpv6(value)).isEqualTo("fd00::1");
  }

  @Test
  void formatIpv6_nullThrows() {
    assertThatThrownBy(() -> Ipv6Util.formatIpv6(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void formatIpv6_negativeThrows() {
    assertThatThrownBy(() -> Ipv6Util.formatIpv6(BigInteger.valueOf(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- subnetNetwork ----

  @Test
  void subnetNetwork_fd00_1_64() {
    BigInteger network = Ipv6Util.subnetNetwork("fd00:1::", 64);
    // fd00:1:: / 64 = fd00:1:: (network address)
    assertThat(Ipv6Util.formatIpv6(network)).isEqualTo("fd00:1::");
  }

  @Test
  void subnetNetwork_2001_db8_48() {
    BigInteger network = Ipv6Util.subnetNetwork("2001:db8::", 48);
    assertThat(Ipv6Util.formatIpv6(network)).isEqualTo("2001:db8::");
  }

  @Test
  void subnetNetwork_invalidBaseThrows() {
    assertThatThrownBy(() -> Ipv6Util.subnetNetwork("not-an-ip", 64))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- roundtrip ----

  @Test
  void roundtrip_parseIpv6_formatIpv6() {
    // Note: ::ffff:x.x.x.x is parsed as Inet4Address by Java, so excluded
    String[] inputs = {"::1", "fd00::1", "2001:db8::1", "fe80::1", "2001:db8:85a3::8a2e:370:7334"};
    for (String input : inputs) {
      BigInteger parsed = Ipv6Util.parseIpv6(input);
      String formatted = Ipv6Util.formatIpv6(parsed);
      BigInteger reparsed = Ipv6Util.parseIpv6(formatted);
      assertThat(reparsed).isEqualTo(parsed);
    }
  }

  @Test
  void roundtrip_canonicalIpv6_formatIpv6() {
    byte[] addr = {
      (byte) 0xfd,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x01
    };
    String canonical = Ipv6Util.canonicalIpv6(addr);
    BigInteger value = Ipv6Util.toUnsigned(addr);
    assertThat(Ipv6Util.formatIpv6(value)).isEqualTo(canonical);
  }
}
