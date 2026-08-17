package com.passagevpn.internal;

import com.passagevpn.common.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Bootstraps the internal control-plane TLS material before the web server starts. Generates an
 * isolated internal CA plus a server certificate (SAN {@code backend}, {@code localhost},
 * 127.0.0.1) and the PKCS12 keystore/truststore consumed by the Tomcat mTLS connector. The whole
 * trust chain lives under {@code PASSAGE_INTERNAL_TLS_DIR} and is kept separate from the VPN PKI so
 * a VPN-side key leak never compromises the control plane.
 *
 * <p>Runs from {@code main()} (before {@code SpringApplication.run}) because the connector needs
 * the keystore at web server startup, before any bean initializer runs. Idempotent: existing
 * artifacts are reused across restarts.
 */
@Slf4j
public final class InternalTlsBootstrap {

  public static final String CA_CERT = "ca.crt";
  public static final String CA_KEY = "ca.key";
  public static final String SERVER_CERT = "backend.crt";
  public static final String SERVER_KEY = "backend.key";
  public static final String KEYSTORE = "backend.p12";
  public static final String TRUSTSTORE = "truststore.p12";
  public static final String KEYSTORE_PASS_FILE = "keystore.pass";
  private static final String SERVER_EXT_FILE = "server-ext.cnf";

  private static final String DEFAULT_TLS_DIR = "./data/internal-tls";
  private static final String CA_SUBJECT = "/CN=opnl-internal-ca";

  private InternalTlsBootstrap() {}

  /** Entry point invoked from {@code main()} before the Spring context starts. */
  public static void ensure() {
    String dir = envOr("PASSAGE_INTERNAL_TLS_DIR", DEFAULT_TLS_DIR);
    ensure(Path.of(dir).toAbsolutePath(), new ProcessRunner());
  }

  /** Package-visible for tests. */
  static void ensure(Path tlsDir, ProcessRunner runner) {
    try {
      Files.createDirectories(tlsDir);
      ensureCa(tlsDir, runner);
      ensureServerCert(tlsDir, runner);
      String password = ensureKeystorePassword(tlsDir);
      ensureKeystore(tlsDir, runner, password);
      ensureTruststore(tlsDir, runner, password);
      log.info("Internal TLS material ready under {}", tlsDir);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot bootstrap internal TLS: " + e.getMessage(), e);
    }
  }

  public static Path tlsDir(String override) {
    return Path.of(override == null || override.isBlank() ? DEFAULT_TLS_DIR : override)
        .toAbsolutePath();
  }

  /** The keystore/truststore password, as persisted by {@link #ensure}. */
  public static String keystorePassword(Path tlsDir) {
    try {
      return Files.readString(tlsDir.resolve(KEYSTORE_PASS_FILE)).trim();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Cannot read internal TLS keystore password at " + tlsDir.resolve(KEYSTORE_PASS_FILE), e);
    }
  }

  private static void ensureCa(Path tlsDir, ProcessRunner runner) throws IOException {
    if (Files.exists(tlsDir.resolve(CA_CERT))) {
      return;
    }
    ProcessRunner.Result run =
        run(
            runner,
            List.of(
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-sha256",
                "-nodes",
                "-keyout",
                tlsDir.resolve(CA_KEY).toString(),
                "-out",
                tlsDir.resolve(CA_CERT).toString(),
                "-days",
                "3650",
                "-subj",
                CA_SUBJECT));
    if (!run.ok()) {
      throw new IllegalStateException("Cannot create internal CA: " + run.stderr());
    }
    chmod600(tlsDir.resolve(CA_KEY));
    log.info("Generated internal CA at {}", tlsDir);
  }

  private static void ensureServerCert(Path tlsDir, ProcessRunner runner) throws IOException {
    if (Files.exists(tlsDir.resolve(SERVER_CERT))) {
      return;
    }
    String ext =
        "subjectAltName = DNS:backend, DNS:localhost, IP:127.0.0.1\n"
            + "basicConstraints = CA:FALSE\n"
            + "keyUsage = digitalSignature, keyEncipherment\n"
            + "extendedKeyUsage = serverAuth\n";
    Files.writeString(tlsDir.resolve(SERVER_EXT_FILE), ext, StandardCharsets.UTF_8);
    ProcessRunner.Result key =
        run(
            runner,
            List.of(
                "openssl",
                "req",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                tlsDir.resolve(SERVER_KEY).toString(),
                "-out",
                tlsDir.resolve(SERVER_CSR).toString(),
                "-subj",
                "/CN=backend"));
    if (!key.ok()) {
      throw new IllegalStateException("Cannot create server key/csr: " + key.stderr());
    }
    ProcessRunner.Result sign =
        run(
            runner,
            List.of(
                "openssl",
                "x509",
                "-req",
                "-in",
                tlsDir.resolve(SERVER_CSR).toString(),
                "-CA",
                tlsDir.resolve(CA_CERT).toString(),
                "-CAkey",
                tlsDir.resolve(CA_KEY).toString(),
                "-CAcreateserial",
                "-out",
                tlsDir.resolve(SERVER_CERT).toString(),
                "-days",
                "365",
                "-sha256",
                "-extfile",
                tlsDir.resolve(SERVER_EXT_FILE).toString()));
    if (!sign.ok()) {
      throw new IllegalStateException("Cannot sign server cert: " + sign.stderr());
    }
    chmod600(tlsDir.resolve(SERVER_KEY));
    Files.deleteIfExists(tlsDir.resolve(SERVER_CSR));
  }

  private static String ensureKeystorePassword(Path tlsDir) throws IOException {
    Path file = tlsDir.resolve(KEYSTORE_PASS_FILE);
    if (Files.exists(file)) {
      return Files.readString(file).trim();
    }
    String password = randomPassword();
    Files.writeString(file, password, StandardCharsets.UTF_8);
    chmod600(file);
    return password;
  }

  private static void ensureKeystore(Path tlsDir, ProcessRunner runner, String password) {
    if (Files.exists(tlsDir.resolve(KEYSTORE))) {
      return;
    }
    ProcessRunner.Result run =
        run(
            runner,
            List.of(
                "openssl",
                "pkcs12",
                "-export",
                "-in",
                tlsDir.resolve(SERVER_CERT).toString(),
                "-inkey",
                tlsDir.resolve(SERVER_KEY).toString(),
                "-certfile",
                tlsDir.resolve(CA_CERT).toString(),
                "-name",
                "backend",
                "-out",
                tlsDir.resolve(KEYSTORE).toString(),
                "-passout",
                "pass:" + password));
    if (!run.ok()) {
      throw new IllegalStateException("Cannot create internal keystore: " + run.stderr());
    }
  }

  private static void ensureTruststore(Path tlsDir, ProcessRunner runner, String password) {
    if (Files.exists(tlsDir.resolve(TRUSTSTORE))) {
      return;
    }
    // Built with keytool, not `openssl pkcs12 -export -nokeys`: the latter produces a PKCS12 that
    // Java's SunPKCS12 provider reads as empty (0 trust anchors), which makes the Tomcat mTLS
    // connector fail with "the trustAnchors parameter must be non-empty".
    ProcessRunner.Result run =
        run(
            runner,
            List.of(
                "keytool",
                "-importcert",
                "-noprompt",
                "-alias",
                "internal-ca",
                "-file",
                tlsDir.resolve(CA_CERT).toString(),
                "-keystore",
                tlsDir.resolve(TRUSTSTORE).toString(),
                "-storetype",
                "PKCS12",
                "-storepass",
                password));
    if (!run.ok()) {
      throw new IllegalStateException("Cannot create internal truststore: " + run.stderr());
    }
  }

  private static ProcessRunner.Result run(ProcessRunner runner, List<String> command) {
    return runner.run(command);
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

  private static void chmod600(Path file) {
    try {
      Files.setPosixFilePermissions(
          file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException e) {
      // Non-POSIX filesystem: rely on the platform defaults.
    }
  }

  private static String envOr(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static final String SERVER_CSR = "backend.csr";
}
