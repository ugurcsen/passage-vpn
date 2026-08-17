package com.passagevpn.internal;

import com.passagevpn.common.ProcessRunner;
import com.passagevpn.config.InternalProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Issues client certificates for remote node agents from the internal CA. Each agent gets its own
 * key material under {@code <tlsDir>/agents/<sanitizedNodeName>/} plus a PKCS12 bundle for the Java
 * client; the CA certificate is exported separately so the agent can pin its trust root.
 */
@Service
public class InternalTlsService {

  private final InternalProperties properties;
  private final ProcessRunner runner;

  public InternalTlsService(InternalProperties properties, ProcessRunner runner) {
    this.properties = properties;
    this.runner = runner;
  }

  /** An agent's issued client certificate material. */
  public record AgentCertificate(
      String nodeName,
      String caCertPem,
      String certPem,
      String keyPem,
      byte[] pkcs12,
      String password) {}

  public Path tlsDir() {
    return InternalTlsBootstrap.tlsDir(properties.tlsDir());
  }

  public String caCertPem() throws IOException {
    return Files.readString(tlsDir().resolve(InternalTlsBootstrap.CA_CERT), StandardCharsets.UTF_8);
  }

  /** Issues a fresh client cert for {@code nodeName}; existing material is overwritten. */
  public AgentCertificate issueAgentCert(String nodeName, String password) {
    Path dir = agentDir(nodeName);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create agent tls dir: " + e.getMessage(), e);
    }
    String ext =
        "extendedKeyUsage = clientAuth\nbasicConstraints = CA:FALSE\nkeyUsage = digitalSignature\n";
    try {
      Files.writeString(dir.resolve("client-ext.cnf"), ext, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot write agent cert ext: " + e.getMessage(), e);
    }
    Path caCert = tlsDir().resolve(InternalTlsBootstrap.CA_CERT);
    Path caKey = tlsDir().resolve(InternalTlsBootstrap.CA_KEY);

    Path csr = dir.resolve("client.csr");
    Path key = dir.resolve("client.key");
    Path cert = dir.resolve("client.crt");
    ProcessRunner.Result csrRun =
        runner.run(
            List.of(
                "openssl",
                "req",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                key.toString(),
                "-out",
                csr.toString(),
                "-subj",
                "/CN=agent-" + nodeName));
    if (!csrRun.ok()) {
      throw new IllegalStateException("Cannot create agent key/csr: " + csrRun.stderr());
    }
    ProcessRunner.Result sign =
        runner.run(
            List.of(
                "openssl",
                "x509",
                "-req",
                "-in",
                csr.toString(),
                "-CA",
                caCert.toString(),
                "-CAkey",
                caKey.toString(),
                "-CAcreateserial",
                "-out",
                cert.toString(),
                "-days",
                "365",
                "-sha256",
                "-extfile",
                dir.resolve("client-ext.cnf").toString()));
    if (!sign.ok()) {
      throw new IllegalStateException("Cannot sign agent cert: " + sign.stderr());
    }
    try {
      Files.deleteIfExists(csr);
    } catch (IOException ignored) {
      // Best effort cleanup.
    }

    String pass = password == null || password.isBlank() ? randomPassword() : password;
    Path p12 = dir.resolve("client.p12");
    ProcessRunner.Result p12Run =
        runner.run(
            List.of(
                "openssl",
                "pkcs12",
                "-export",
                "-in",
                cert.toString(),
                "-inkey",
                key.toString(),
                "-certfile",
                caCert.toString(),
                "-name",
                "agent-" + nodeName,
                "-out",
                p12.toString(),
                "-passout",
                "pass:" + pass));
    if (!p12Run.ok()) {
      throw new IllegalStateException("Cannot export agent pkcs12: " + p12Run.stderr());
    }
    try {
      Files.writeString(dir.resolve("client.pass"), pass, StandardCharsets.UTF_8);
      chmod600(key);
      chmod600(dir.resolve("client.pass"));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot persist agent password: " + e.getMessage(), e);
    }

    try {
      return new AgentCertificate(
          nodeName,
          Files.readString(caCert, StandardCharsets.UTF_8),
          Files.readString(cert, StandardCharsets.UTF_8),
          Files.readString(key, StandardCharsets.UTF_8),
          Files.readAllBytes(p12),
          pass);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read issued agent material: " + e.getMessage(), e);
    }
  }

  /** Sanitized per-agent directory name. */
  public Path agentDir(String nodeName) {
    String safe = nodeName.replaceAll("[^a-zA-Z0-9_.-]", "_");
    return tlsDir().resolve("agents").resolve(safe);
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

  private static void chmod600(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(
          file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem: rely on platform defaults.
    }
  }
}
