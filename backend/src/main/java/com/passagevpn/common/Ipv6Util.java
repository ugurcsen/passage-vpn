package com.passagevpn.common;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Stateless IPv6 utility methods. All BigInteger arithmetic for prefix masking, network
 * calculation, canonical formatting, and unsigned conversion lives here so {@code CcdService} and
 * pool allocators stay focused on CCD/pool logic.
 */
public final class Ipv6Util {

  private Ipv6Util() {}

  /**
   * RFC 5952 canonical form of a 16-byte IPv6 address. The longest run of consecutive zero groups
   * is compressed to {@code ::}; when two runs have equal length, the leftmost is compressed.
   */
  public static String canonicalIpv6(byte[] address) {
    if (address == null || address.length != 16) {
      throw new IllegalArgumentException("IPv6 address must be exactly 16 bytes");
    }
    int[] groups = new int[8];
    for (int i = 0; i < 8; i++) {
      groups[i] = ((address[i * 2] & 0xFF) << 8) | (address[i * 2 + 1] & 0xFF);
    }
    int bestStart = -1;
    int bestLen = 1;
    for (int i = 0; i < 8; ) {
      if (groups[i] == 0) {
        int j = i;
        while (j < 8 && groups[j] == 0) {
          j++;
        }
        if (j - i >= 2 && j - i > bestLen) {
          bestStart = i;
          bestLen = j - i;
        }
        i = j;
      } else {
        i++;
      }
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      if (i == bestStart) {
        sb.append("::");
        i += bestLen - 1;
        continue;
      }
      if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
        sb.append(':');
      }
      sb.append(Integer.toHexString(groups[i]));
    }
    return sb.length() == 0 ? "::" : sb.toString();
  }

  /** The IPv6 prefix mask as an unsigned BigInteger for the given prefix length (0–128). */
  public static BigInteger prefixMask(int prefix) {
    if (prefix < 0 || prefix > 128) {
      throw new IllegalArgumentException("Prefix length must be 0–128, got " + prefix);
    }
    BigInteger full = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
    BigInteger host = BigInteger.ONE.shiftLeft(128 - prefix).subtract(BigInteger.ONE);
    return full.xor(host);
  }

  /**
   * Applies a prefix mask to raw IPv6 address bytes, zeroing out the host bits. Returns a new
   * 16-byte array containing the network address.
   */
  public static byte[] maskToNetwork(byte[] addressBytes, int prefix) {
    if (addressBytes == null) {
      throw new IllegalArgumentException("addressBytes must not be null");
    }
    if (prefix < 0 || prefix > 128) {
      throw new IllegalArgumentException("Prefix length must be 0–128, got " + prefix);
    }
    byte[] out = addressBytes.clone();
    int skip = Math.max(0, out.length - 16);
    int fullBytes = prefix / 8;
    int remBits = prefix % 8;
    for (int i = fullBytes + skip; i < 16 + skip; i++) {
      out[i] = 0;
    }
    if (remBits != 0) {
      out[fullBytes + skip] &= (byte) (0xFF << (8 - remBits));
    }
    return out;
  }

  /** Converts a 16-byte unsigned representation to a non-negative BigInteger. */
  public static BigInteger toUnsigned(byte[] bytes) {
    if (bytes == null) {
      throw new IllegalArgumentException("bytes must not be null");
    }
    return new BigInteger(1, bytes);
  }

  /**
   * Parses an IPv6 literal string to an unsigned BigInteger. Throws {@link
   * IllegalArgumentException} if the input is not a valid IPv6 address.
   */
  public static BigInteger parseIpv6(String ip) {
    if (ip == null || ip.isBlank()) {
      throw new IllegalArgumentException("IPv6 address must not be blank");
    }
    try {
      InetAddress address = InetAddress.getByName(ip.trim());
      if (!(address instanceof Inet6Address)) {
        throw new IllegalArgumentException("Not an IPv6 address: " + ip);
      }
      return toUnsigned(address.getAddress());
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid IPv6 address: " + ip, e);
    }
  }

  /**
   * Formats an unsigned BigInteger as a canonical IPv6 string (RFC 5952). The value must fit in 128
   * bits (0 to 2^128 − 1).
   */
  public static String formatIpv6(BigInteger value) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (value.signum() < 0) {
      throw new IllegalArgumentException("IPv6 value must be non-negative");
    }
    byte[] bytes = value.toByteArray();
    byte[] result = new byte[16];
    if (bytes.length >= 16) {
      System.arraycopy(bytes, bytes.length - 16, result, 0, 16);
    } else {
      System.arraycopy(bytes, 0, result, 16 - bytes.length, bytes.length);
    }
    return canonicalIpv6(result);
  }

  /**
   * Computes the network address (as unsigned BigInteger) of an IPv6 subnet given the subnet base
   * address and prefix length.
   */
  public static BigInteger subnetNetwork(String subnetBase, int prefix) {
    try {
      InetAddress subnet = InetAddress.getByName(subnetBase);
      return toUnsigned(maskToNetwork(subnet.getAddress(), prefix));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid IPv6 subnet: " + subnetBase + "/" + prefix, e);
    }
  }
}
