package com.opnl.vpn.auth;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.util.Base64;
import org.springframework.stereotype.Service;

/** TOTP (RFC 6238) helpers for MFA provisioning and verification. */
@Service
public class TotpService {

  private static final String ISSUER = "OpenVPN Panel";
  private static final int SECRET_BITS = 160;

  private final SecretGenerator secretGenerator = new DefaultSecretGenerator(SECRET_BITS);
  private final QrGenerator qrGenerator = new ZxingPngQrGenerator();
  private final DefaultCodeVerifier verifier;

  public TotpService() {
    var codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    this.verifier = new DefaultCodeVerifier(codeGenerator, new SystemTimeProvider());
    this.verifier.setAllowedTimePeriodDiscrepancy(1);
  }

  /** Fresh Base32 secret for a user. */
  public String generateSecret() {
    return secretGenerator.generate();
  }

  /** Verifies a 6-digit code against a Base32 secret within the allowed clock skew. */
  public boolean verify(String secret, String code) {
    return secret != null && code != null && verifier.isValidCode(secret, code);
  }

  /** otpauth:// URI for the authenticator app. */
  public String otpAuthUri(String secret, String username) {
    return qrData(secret, username).getUri();
  }

  /** Base64 PNG data URL of the QR code for the provisioning URI. */
  public String qrPngDataUrl(String secret, String username) {
    try {
      return "data:image/png;base64,"
          + Base64.getEncoder().encodeToString(qrGenerator.generate(qrData(secret, username)));
    } catch (QrGenerationException e) {
      throw new IllegalStateException("Failed to render TOTP QR code", e);
    }
  }

  private QrData qrData(String secret, String username) {
    return new QrData.Builder()
        .label(username)
        .secret(secret)
        .issuer(ISSUER)
        .algorithm(HashingAlgorithm.SHA1)
        .digits(6)
        .period(30)
        .build();
  }
}
