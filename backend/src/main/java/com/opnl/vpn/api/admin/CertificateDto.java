package com.opnl.vpn.api.admin;

import com.opnl.vpn.pki.Certificate;
import com.opnl.vpn.pki.Certificate.Status;
import java.time.Instant;

/** Admin-facing certificate representation. */
public record CertificateDto(
    String id,
    String commonName,
    String userId,
    String username,
    Status status,
    String serial,
    Instant issuedAt,
    Instant expiresAt,
    Instant revokedAt) {

  public static CertificateDto from(Certificate certificate, String username) {
    return new CertificateDto(
        certificate.getId(),
        certificate.getCommonName(),
        certificate.getUserId(),
        username,
        certificate.getStatus(),
        certificate.getSerial(),
        certificate.getIssuedAt(),
        certificate.getExpiresAt(),
        certificate.getRevokedAt());
  }
}
