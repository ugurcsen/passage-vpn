package com.opnl.vpn.node;

import com.opnl.vpn.common.ProcessRunner;
import com.opnl.vpn.config.AgentProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds the agent's outbound mTLS context from PEM material issued by the central internal CA
 * ({@code opnl.agent.tls-ca|cert|key}). The client key is converted to a PKCS12 bundle via {@code
 * openssl} (present in the agent image) so it can be loaded into a Java {@link KeyStore}; the CA is
 * read directly as a trust anchor. The resulting context is cached per process.
 */
@Slf4j
@Component
public class AgentTls {

  private final AgentProperties properties;
  private final ProcessRunner runner;

  private volatile SSLContext cached;

  public AgentTls(AgentProperties properties, ProcessRunner runner) {
    this.properties = properties;
    this.runner = runner;
  }

  /** True when all three TLS material paths are configured. */
  public boolean configured() {
    return notBlank(properties.tlsCa())
        && notBlank(properties.tlsCert())
        && notBlank(properties.tlsKey());
  }

  /** The mTLS context used to talk to the central backend, built lazily and cached. */
  public SSLContext sslContext() {
    SSLContext current = cached;
    if (current == null) {
      synchronized (this) {
        current = cached;
        if (current == null) {
          current = build();
          cached = current;
        }
      }
    }
    return current;
  }

  private SSLContext build() {
    try {
      Path certPem = Path.of(properties.tlsCert());
      Path keyPem = Path.of(properties.tlsKey());
      char[] pass = randomPassword().toCharArray();
      Path tempP12 = Files.createTempFile("agent-client", ".p12");
      try {
        ProcessRunner.Result exported =
            runner.run(
                List.of(
                    "openssl",
                    "pkcs12",
                    "-export",
                    "-in",
                    certPem.toString(),
                    "-inkey",
                    keyPem.toString(),
                    "-name",
                    "agent",
                    "-out",
                    tempP12.toString(),
                    "-passout",
                    "pass:" + new String(pass)));
        if (!exported.ok()) {
          throw new IllegalStateException("Cannot build agent PKCS12 bundle: " + exported.stderr());
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(tempP12)) {
          keyStore.load(in, pass);
        } catch (IOException e) {
          throw new IllegalStateException("Cannot load agent PKCS12 bundle: " + e.getMessage(), e);
        }
        KeyManagerFactory kmf =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, pass);

        TrustManagerFactory tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(readTrustStore(Path.of(properties.tlsCa())));

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        log.info("Agent mTLS context built from central CA {}", properties.tlsCa());
        return context;
      } finally {
        Files.deleteIfExists(tempP12);
      }
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("Cannot configure agent TLS: " + e.getMessage(), e);
    }
  }

  /** Loads a PEM CA bundle (one or more certificates) into a trust store. */
  private static KeyStore readTrustStore(Path caPem) throws GeneralSecurityException, IOException {
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    try (var in = Files.newInputStream(caPem)) {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      for (var cert : factory.generateCertificates(in)) {
        trustStore.setCertificateEntry(
            "ca-" + System.nanoTime(), (java.security.cert.Certificate) cert);
      }
    }
    return trustStore;
  }

  private static String randomPassword() {
    String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    SecureRandom random = new SecureRandom();
    StringBuilder sb = new StringBuilder(32);
    for (int i = 0; i < 32; i++) {
      sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return sb.toString();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
