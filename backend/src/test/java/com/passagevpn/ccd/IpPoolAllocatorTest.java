package com.passagevpn.ccd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.passagevpn.common.ApiException;
import com.passagevpn.common.Ipv6Util;
import java.math.BigInteger;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IpPoolAllocatorTest {

  // ── IPv4 ──────────────────────────────────────────────────────────────────

  @Nested
  class Ipv4 {

    private final IpPoolAllocator<Long> allocator =
        IpPoolAllocator.ipv4(0x0A080001L, 0x0A0800FFL); // 10.8.0.1 – 10.8.0.255

    @Test
    void parseValidRange() {
      var range = allocator.parse("10.8.0.10-10.8.0.20");
      assertThat(range.start()).isEqualTo(0x0A08000AL);
      assertThat(range.end()).isEqualTo(0x0A080014L);
    }

    @Test
    void parseSingleHost() {
      var range = allocator.parse("10.8.0.42-10.8.0.42");
      assertThat(range.start()).isEqualTo(range.end());
    }

    @Test
    void parseRejectsMissingSeparator() {
      assertThatThrownBy(() -> allocator.parse("10.8.0.10")).isInstanceOf(ApiException.class);
    }

    @Test
    void parseRejectsStartGreaterThanEnd() {
      assertThatThrownBy(() -> allocator.parse("10.8.0.20-10.8.0.10"))
          .isInstanceOf(ApiException.class);
    }

    @Test
    void parseRejectsInvalidIp() {
      assertThatThrownBy(() -> allocator.parse("xyz-10.8.0.20"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsIpv6Address() {
      assertThatThrownBy(() -> allocator.parse("fd00::10-fd00::20"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsNetworkAddress() {
      assertThatThrownBy(() -> allocator.parse("10.8.0.0-10.8.0.10"))
          .isInstanceOf(ApiException.class);
    }

    @Test
    void parseRejectsBroadcastAddress() {
      assertThatThrownBy(() -> allocator.parse("10.8.0.10-10.8.0.255"))
          .isInstanceOf(ApiException.class);
    }

    @Test
    void findFreeReturnsFirstAvailable() {
      var range = allocator.parse("10.8.0.10-10.8.0.14");
      assertThat(allocator.findFree(range, Set.of())).isEqualTo("10.8.0.10");
    }

    @Test
    void findFreeSkipsUsedAddresses() {
      var range = allocator.parse("10.8.0.10-10.8.0.14");
      assertThat(allocator.findFree(range, Set.of("10.8.0.10", "10.8.0.11")))
          .isEqualTo("10.8.0.12");
    }

    @Test
    void findFreeReturnsNullWhenExhausted() {
      var range = allocator.parse("10.8.0.10-10.8.0.11");
      assertThat(allocator.findFree(range, Set.of("10.8.0.10", "10.8.0.11"))).isNull();
    }

    @Test
    void findFreeRejectsRangeLargerThan65536() {
      IpPoolAllocator<Long> big = IpPoolAllocator.ipv4(0x0A000000L, 0x0AFFFFFFL);
      var range = big.parse("10.0.0.10-10.1.0.10");
      assertThatThrownBy(() -> big.findFree(range, Set.of())).isInstanceOf(ApiException.class);
    }

    @Test
    void formatIpv4() {
      assertThat(allocator.format(0x0A08000AL)).isEqualTo("10.8.0.10");
      assertThat(allocator.format(0x0A0800FFL)).isEqualTo("10.8.0.255");
    }
  }

  // ── IPv6 ──────────────────────────────────────────────────────────────────

  @Nested
  class Ipv6 {

    private final BigInteger NETWORK = Ipv6Util.parseIpv6("fd00:1::");
    private final BigInteger END = Ipv6Util.parseIpv6("fd00:1::ffff");
    private final IpPoolAllocator<BigInteger> allocator = IpPoolAllocator.ipv6(NETWORK, END);

    @Test
    void parseValidRange() {
      var range = allocator.parse("fd00:1::10-fd00:1::20");
      assertThat(range.start()).isEqualTo(Ipv6Util.parseIpv6("fd00:1::10"));
      assertThat(range.end()).isEqualTo(Ipv6Util.parseIpv6("fd00:1::20"));
    }

    @Test
    void parseRejectsMissingSeparator() {
      assertThatThrownBy(() -> allocator.parse("fd00:1::10")).isInstanceOf(ApiException.class);
    }

    @Test
    void parseRejectsInvalidIpv6() {
      assertThatThrownBy(() -> allocator.parse("xyz-fd00:1::20"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsIpv4Address() {
      assertThatThrownBy(() -> allocator.parse("10.8.0.10-10.8.0.20"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsStartGreaterThanEnd() {
      assertThatThrownBy(() -> allocator.parse("fd00:1::20-fd00:1::10"))
          .isInstanceOf(ApiException.class);
    }

    @Test
    void findFreeReturnsFirstAvailable() {
      var range = allocator.parse("fd00:1::10-fd00:1::14");
      assertThat(allocator.findFree(range, Set.of())).isEqualTo("fd00:1::10");
    }

    @Test
    void findFreeSkipsUsedAddresses() {
      var range = allocator.parse("fd00:1::10-fd00:1::14");
      assertThat(allocator.findFree(range, Set.of("fd00:1::10", "fd00:1::11")))
          .isEqualTo("fd00:1::12");
    }

    @Test
    void findFreeReturnsNullWhenExhausted() {
      var range = allocator.parse("fd00:1::10-fd00:1::11");
      assertThat(allocator.findFree(range, Set.of("fd00:1::10", "fd00:1::11"))).isNull();
    }

    @Test
    void findFreeRejectsRangeLargerThan65536() {
      BigInteger bigEnd = Ipv6Util.parseIpv6("fd00:1::ffff:ffff:ffff:ffff");
      IpPoolAllocator<BigInteger> big = IpPoolAllocator.ipv6(NETWORK, bigEnd);
      var range = big.parse("fd00:1::10-fd00:1::1:0010");
      assertThatThrownBy(() -> big.findFree(range, Set.of())).isInstanceOf(ApiException.class);
    }

    @Test
    void formatIpv6Roundtrip() {
      assertThat(allocator.format(NETWORK.add(BigInteger.ONE))).isEqualTo("fd00:1::1");
      assertThat(allocator.format(END)).isEqualTo("fd00:1::ffff");
    }
  }
}
