package com.opnl.vpn.pki;

import com.opnl.vpn.common.ApiException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Parser for Easy-RSA's index.txt (the certificate database file). */
@Component
public class IndexParser {

  private static final DateTimeFormatter EXPIRY =
      DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

  /** Parses index.txt content into certificate index entries. */
  public List<CertIndexEntry> parse(String content) {
    List<CertIndexEntry> entries = new ArrayList<>();
    if (content == null || content.isBlank()) {
      return entries;
    }
    for (String line : content.split("\\R")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\\t", -1);
      if (parts.length < 5) {
        continue;
      }
      entries.add(
          new CertIndexEntry(
              mapStatus(parts[0]), parseExpiry(parts[1]), parts[2], parts[3], parts[4]));
    }
    return entries;
  }

  private CertIndexEntry.Status mapStatus(String s) {
    return switch (s) {
      case "V" -> CertIndexEntry.Status.VALID;
      case "R" -> CertIndexEntry.Status.REVOKED;
      case "E" -> CertIndexEntry.Status.EXPIRED;
      default -> CertIndexEntry.Status.EXPIRED;
    };
  }

  private Instant parseExpiry(String s) {
    try {
      return Instant.from(EXPIRY.parse(s));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Fails fast with a descriptive error when the PKI is not initialized. */
  public static ApiException missingPki() {
    return ApiException.conflict("pki_not_initialized", "PKI has not been initialized yet");
  }
}
