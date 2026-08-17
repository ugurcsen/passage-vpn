package com.passagevpn.pki;

import com.passagevpn.common.ApiException;
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
      if (parts.length >= 6) {
        // Easy-RSA 3 index.txt: status, expiry, revocation date, serial, filename, subject.
        entries.add(
            new CertIndexEntry(
                mapStatus(parts[0]),
                parseExpiry(parts[1]),
                parseRevokedAt(parts[2]),
                parts[3],
                parts[4],
                commonNameOf(parts[5])));
      } else {
        // Legacy 5-column rows: status, expiry, serial, filename, common name.
        entries.add(
            new CertIndexEntry(
                mapStatus(parts[0]), parseExpiry(parts[1]), parts[2], parts[3], parts[4]));
      }
    }
    return entries;
  }

  private Instant parseRevokedAt(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return Instant.from(EXPIRY.parse(s));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Extracts the common name from an X.500 subject (e.g. {@code /CN=alice}). */
  private String commonNameOf(String subject) {
    if (subject == null) {
      return null;
    }
    int idx = subject.indexOf("/CN=");
    if (idx < 0) {
      return null;
    }
    String cn = subject.substring(idx + "/CN=".length());
    int slash = cn.indexOf('/');
    return slash < 0 ? cn : cn.substring(0, slash);
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
