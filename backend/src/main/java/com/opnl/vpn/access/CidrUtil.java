package com.opnl.vpn.access;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IPv4/IPv6 CIDR containment helpers used to detect overlap between access-rule destinations and
 * scoped DNS-override addresses. Purely in-memory and dependency-free; malformed input never throws
 * and is treated as "no match".
 */
public final class CidrUtil {

  private CidrUtil() {}

  /**
   * Whether the address {@code ip} falls inside {@code cidr}. Family mismatches (an IPv4 CIDR vs an
   * IPv6 address) and malformed input return {@code false}.
   */
  public static boolean contains(String cidr, String ip) {
    if (cidr == null || ip == null || cidr.isBlank() || ip.isBlank()) {
      return false;
    }
    int slash = cidr.indexOf('/');
    if (slash <= 0) {
      return false;
    }
    int prefix;
    try {
      prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
    } catch (NumberFormatException e) {
      return false;
    }
    byte[] network;
    byte[] address;
    try {
      network = InetAddress.getByName(cidr.substring(0, slash).trim()).getAddress();
      address = InetAddress.getByName(ip.trim()).getAddress();
    } catch (UnknownHostException e) {
      return false;
    }
    if (network.length != address.length) {
      return false;
    }
    int maxPrefix = network.length * 8;
    if (prefix < 0 || prefix > maxPrefix) {
      return false;
    }
    int fullBytes = prefix / 8;
    int remainingBits = prefix % 8;
    for (int i = 0; i < fullBytes; i++) {
      if (network[i] != address[i]) {
        return false;
      }
    }
    if (remainingBits > 0) {
      int mask = 0xff << (8 - remainingBits);
      if ((network[fullBytes] & mask) != (address[fullBytes] & mask)) {
        return false;
      }
    }
    return true;
  }
}
