package com.passagevpn.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import org.springframework.stereotype.Component;

/**
 * Extracts the mTLS client certificate from an incoming request. The servlet container exposes the
 * verified peer chain as the {@code jakarta.servlet.request.X509Certificate} request attribute;
 * this component reads it and returns the subject CN so callers can authorize by node identity.
 */
@Component
public class ClientCertReader {

  /** Subject CN of the peer certificate, or {@code null} if no client cert was presented. */
  public String subjectCn(HttpServletRequest request) {
    X509Certificate[] chain = certificateChain(request);
    if (chain == null || chain.length == 0) {
      return null;
    }
    return cnOf(chain[0]);
  }

  private X509Certificate[] certificateChain(HttpServletRequest request) {
    Object attr = request.getAttribute("jakarta.servlet.request.X509Certificate");
    if (attr instanceof X509Certificate[] certs) {
      return certs;
    }
    return null;
  }

  /** Parses the first certificate of a PEM bundle (used in tests). */
  public X509Certificate parsePem(String pem) {
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      return (X509Certificate)
          factory.generateCertificate(
              new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (CertificateException e) {
      throw new IllegalArgumentException("Invalid PEM certificate", e);
    }
  }

  private static String cnOf(X509Certificate certificate) {
    String dn = certificate.getSubjectX500Principal().getName();
    for (String part : dn.split(",")) {
      String trimmed = part.trim();
      if (trimmed.startsWith("CN=")) {
        return trimmed.substring(3);
      }
    }
    return null;
  }
}
