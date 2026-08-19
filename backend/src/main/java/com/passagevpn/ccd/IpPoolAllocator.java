package com.passagevpn.ccd;

import com.passagevpn.common.ApiException;
import com.passagevpn.common.Ipv6Util;
import java.math.BigInteger;
import java.util.Set;
import java.util.function.Function;

/**
 * Generic IP pool allocator. Parses a pool expression of the form {@code start-end} and finds the
 * first free address in the range. Supports both IPv4 (represented as {@code long}) and IPv6
 * (represented as {@code BigInteger}).
 *
 * @param <T> the address representation type ({@code Long} for IPv4, {@code BigInteger} for IPv6)
 */
final class IpPoolAllocator<T extends Comparable<T>> {

  private final Function<String, T> parser;
  private final Function<T, String> formatter;
  private final Function<T, T> successor;
  private final T networkAddress;
  private final T broadcastAddress;
  private final String errorCode;

  IpPoolAllocator(
      Function<String, T> parser,
      Function<T, String> formatter,
      Function<T, T> successor,
      T networkAddress,
      T broadcastAddress,
      String errorCode) {
    this.parser = parser;
    this.formatter = formatter;
    this.successor = successor;
    this.networkAddress = networkAddress;
    this.broadcastAddress = broadcastAddress;
    this.errorCode = errorCode;
  }

  /**
   * Parses a pool expression and validates that both endpoints are host addresses inside the subnet
   * (excluding network and broadcast).
   */
  PoolRange<T> parse(String pool) {
    String[] parts = pool.trim().split("-");
    if (parts.length != 2) {
      throw ApiException.badRequest(
          errorCode, formatPoolError("pool must be of the form start-end"));
    }
    T start = parser.apply(parts[0].trim());
    T end = parser.apply(parts[1].trim());
    if (start.compareTo(end) > 0) {
      throw ApiException.badRequest(
          errorCode, formatPoolError("start must not be greater than end"));
    }
    if (start.compareTo(networkAddress) <= 0 || end.compareTo(broadcastAddress) >= 0) {
      throw ApiException.badRequest(
          errorCode, formatPoolError("pool must be host addresses inside the subnet"));
    }
    return new PoolRange<>(start, end);
  }

  /**
   * Finds the first free address in the range that is not in the {@code used} set and is not the
   * user's own current address ({@code selfIp}). Returns {@code null} when every address in the
   * range is in use.
   */
  String findFree(PoolRange<T> range, Set<String> used) {
    long guard = estimateSize(range);
    if (guard > 65536) {
      throw ApiException.badRequest(errorCode, formatPoolError("pool range is too large"));
    }
    for (T candidate = range.start(); ; candidate = successor.apply(candidate)) {
      String ip = formatter.apply(candidate);
      if (!used.contains(ip)) {
        return ip;
      }
      if (candidate.compareTo(range.end()) >= 0) {
        break;
      }
    }
    return null;
  }

  /** Formats a parsed value back to its string representation. */
  String format(T value) {
    return formatter.apply(value);
  }

  private long estimateSize(PoolRange<T> range) {
    if (range.start() instanceof Long a && range.end() instanceof Long b) {
      return b - a + 1;
    }
    if (range.start() instanceof BigInteger a && range.end() instanceof BigInteger b) {
      return b.subtract(a).add(BigInteger.ONE).longValueExact();
    }
    return 0;
  }

  private String formatPoolError(String message) {
    return errorCode.contains("ipv6") ? "IPv6 " + message : "IP " + message;
  }

  record PoolRange<T>(T start, T end) {}

  // ---- factory methods ----

  /** Creates an IPv4 pool allocator using the server subnet bounds. */
  static IpPoolAllocator<Long> ipv4(long networkAddr, long broadcastAddr) {
    return new IpPoolAllocator<>(
        IpPoolAllocator::parseIpv4,
        IpPoolAllocator::formatIpv4,
        v -> v + 1,
        networkAddr,
        broadcastAddr,
        "invalid_ip_pool");
  }

  /** Creates an IPv6 pool allocator using the server subnet network and host mask. */
  static IpPoolAllocator<BigInteger> ipv6(BigInteger networkAddr, BigInteger broadcastAddr) {
    return new IpPoolAllocator<>(
        Ipv6Util::parseIpv6,
        Ipv6Util::formatIpv6,
        v -> v.add(BigInteger.ONE),
        networkAddr,
        broadcastAddr,
        "invalid_ipv6_pool");
  }

  private static Long parseIpv4(String ip) {
    try {
      String[] octets = ip.trim().split("\\.");
      if (octets.length != 4) {
        throw new IllegalArgumentException("Not an IPv4 address: " + ip);
      }
      long value = 0;
      for (String octet : octets) {
        int o = Integer.parseInt(octet);
        if (o < 0 || o > 255) {
          throw new IllegalArgumentException("Invalid octet: " + octet);
        }
        value = (value << 8) | o;
      }
      return value;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid IPv4 address: " + ip, e);
    }
  }

  private static String formatIpv4(long value) {
    return ((value >> 24) & 0xff)
        + "."
        + ((value >> 16) & 0xff)
        + "."
        + ((value >> 8) & 0xff)
        + "."
        + (value & 0xff);
  }
}
