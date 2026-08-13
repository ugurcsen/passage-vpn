package com.opnl.vpn.pki;

/** A parsed row from Easy-RSA's index.txt. */
public record CertIndexEntry(
    Status status,
    java.time.Instant expiry,
    java.time.Instant revokedAt,
    String serial,
    String filename,
    String commonName) {

  public CertIndexEntry(
      Status status, java.time.Instant expiry, String serial, String filename, String commonName) {
    this(status, expiry, null, serial, filename, commonName);
  }

  public enum Status {
    VALID,
    REVOKED,
    EXPIRED
  }
}
