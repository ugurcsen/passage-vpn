package com.opnl.vpn.pki;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.common.ProcessRunner;
import com.opnl.vpn.config.OpnlProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around the Easy-RSA CLI. The backend is the single PKI authority: it owns the pki
 * directory and exports artifacts (ca.crt, server cert, ta.key, crl.pem) to the shared volume
 * consumed by the OpenVPN container.
 *
 * <p>All operations run with {@code EASYRSA_PKI} pinned to the configured pki dir and are
 * serialized (easyrsa is not safe under concurrent invocation).
 */
@Slf4j
@Service
public class EasyRsaService {

  private final Path pkiDir;
  private final ProcessRunner runner;
  private final String easyrsaBin;
  private final int certExpireDays;

  public EasyRsaService(OpnlProperties properties, ProcessRunner runner) {
    this.pkiDir = Path.of(properties.openvpn().pkiDir()).toAbsolutePath();
    this.runner = runner;
    this.easyrsaBin = properties.openvpn().easyrsaBin();
    this.certExpireDays = properties.openvpn().certExpireDays();
  }

  /** Client/server certificate validity in days (Easy-RSA {@code EASYRSA_CERT_EXPIRE}). */
  public int certExpireDays() {
    return certExpireDays;
  }

  public Path pkiDir() {
    return pkiDir;
  }

  /** True when the CA has been initialized (ca.crt exists). */
  public synchronized boolean isInitialized() {
    return Files.exists(pkiDir.resolve("ca.crt"));
  }

  /**
   * Initializes the PKI: init-pki + build-ca (no passphrase) + generates the shared TLS key
   * (ta.key) used for tls-crypt.
   */
  public synchronized void initPki() {
    if (isInitialized()) {
      return;
    }
    try {
      Files.createDirectories(pkiDir);
    } catch (IOException e) {
      throw ApiException.internal("pki_init", "Cannot create PKI dir: " + e.getMessage());
    }
    runEasyrsa(List.of("init-pki"));
    runEasyrsa(List.of("--batch", "build-ca", "nopass"));

    Path taKey = pkiDir.resolve("ta.key");
    if (!Files.exists(taKey)) {
      ProcessRunner.Result r =
          runner.run(
              List.of("openvpn", "--genkey", "secret", taKey.toString()),
              Map.of(),
              java.time.Duration.ofSeconds(30));
      if (!r.ok()) {
        throw ApiException.internal("pki_init", "Failed to generate ta.key: " + r.stderr());
      }
    }
    log.info("PKI initialized at {}", pkiDir);
  }

  /** Builds the server certificate/key pair. Idempotent when already present. */
  public synchronized void buildServerCert(String name) {
    if (!isInitialized()) {
      throw IndexParser.missingPki();
    }
    Path issuedCert = pkiDir.resolve("issued").resolve(name + ".crt");
    Path privateKey = pkiDir.resolve("private").resolve(name + ".key");
    if (!Files.exists(issuedCert) || !Files.exists(privateKey)) {
      runEasyrsa(List.of("--batch", "build-server-full", name, "nopass"));
    }
    // OpenVPN references flat paths in the pki root; easyrsa stores them in
    // subdirectories, so expose copies at the root for the daemon config.
    copyToRoot(issuedCert, name + ".crt");
    copyToRoot(privateKey, name + ".key");
  }

  private void copyToRoot(Path source, String targetName) {
    try {
      Files.copy(
          source, pkiDir.resolve(targetName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw ApiException.internal("pki_copy", "Cannot copy " + source + ": " + e.getMessage());
    }
  }

  /** Issues a client certificate for the given common name. */
  public synchronized void issueClientCert(String cn) {
    requirePki();
    runEasyrsa(List.of("--batch", "build-client-full", cn, "nopass"));
  }

  /** Revokes a certificate by common name and regenerates the CRL. */
  public synchronized void revokeCert(String cn) {
    requirePki();
    runEasyrsa(List.of("--batch", "revoke", cn));
    genCrl();
  }

  /**
   * Restores a previously revoked certificate by flipping its index.txt entry back to valid
   * (Easy-RSA 3.1 has no unrevoke command) and regenerating the CRL so the cert is accepted again.
   *
   * <p>Matching is by serial when one is available, falling back to the common name for legacy
   * certificates whose serial was never recorded in the database.
   *
   * @throws ApiException {@code certificate_not_found} when no revoked entry carries the serial,
   *     {@code not_revoked} when the entry exists but is not in the revoked state
   */
  public synchronized void unrevokeCert(String serial, String commonName) {
    requirePki();
    Path indexFile = pkiDir.resolve("index.txt");
    List<String> lines;
    try {
      lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ApiException.internal("pki_index", "Cannot read index.txt: " + e.getMessage());
    }
    boolean matchBySerial = serial != null && !serial.isBlank();
    boolean matchByCommonName = commonName != null && !commonName.isBlank();
    if (!matchBySerial && !matchByCommonName) {
      throw ApiException.internal(
          "pki_index", "Cannot locate the certificate: no serial or common name to match");
    }
    java.util.ArrayList<String> updated = new java.util.ArrayList<>(lines.size());
    boolean restored = false;
    boolean found = false;
    String restoredSerial = null;
    String restoredCn = null;
    for (String line : lines) {
      String[] parts = line.split("\\t", -1);
      // index.txt row: status, expiry, revocation date, serial, filename, CN
      boolean matches =
          matchBySerial
              ? parts.length >= 6 && serial.equals(parts[3])
              : parts.length >= 6 && ("/CN=" + commonName).equals(parts[5]);
      if (!matches) {
        updated.add(line);
        continue;
      }
      found = true;
      if ("R".equals(parts[0])) {
        restoredSerial = parts[3];
        restoredCn = parts[5].startsWith("/CN=") ? parts[5].substring(4) : parts[5];
        updated.add("V\t" + parts[1] + "\t\t" + parts[3] + "\t" + parts[4] + "\t" + parts[5]);
        restored = true;
      } else {
        updated.add(line);
      }
    }
    String target = matchBySerial ? "serial " + serial : "common name " + commonName;
    if (!found) {
      throw ApiException.notFound(
          "certificate_not_found", "No certificate with " + target + " in the PKI index");
    }
    if (!restored) {
      throw ApiException.conflict("not_revoked", "Certificate with " + target + " is not revoked");
    }
    // Restore the on-disk artifacts BEFORE touching the index so a restore can never leave a VALID
    // row whose certificate is unusable. Easy-RSA moves issued/<cn>.crt to
    // certs_by_serial/<serial>.pem and private/<cn>.key to revoked/private_by_serial/<serial>.key
    // on revoke; without putting them back a later revoke/rotate fails with
    // "Unable to revoke as no certificate was found".
    String targetSerial = matchBySerial ? serial : restoredSerial;
    String targetCn = commonName != null ? commonName : restoredCn;
    restoreRevokedArtifacts(targetSerial, targetCn);
    try {
      Files.write(indexFile, updated, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ApiException.internal("pki_index", "Cannot write index.txt: " + e.getMessage());
    }
    genCrl();
  }

  /**
   * Restores the certificate (and, when present, its private key) that Easy-RSA moved away on
   * revoke. The certificate is mandatory — without it any subsequent revoke or rotate fails — while
   * the key is restored best-effort when the revoked-key archive still holds it.
   *
   * @throws ApiException {@code pki_missing} when the revoked certificate artifact cannot be
   *     located; the caller must not flip the index in that case
   */
  private void restoreRevokedArtifacts(String serial, String commonName) {
    if (serial == null || serial.isBlank()) {
      // Legacy row without a recorded serial: nothing to locate on disk, keep the index-only
      // restore behaviour for this path.
      return;
    }
    Path certSource = pkiDir.resolve("certs_by_serial").resolve(serial + ".pem");
    if (!Files.exists(certSource)) {
      certSource =
          pkiDir
              .resolve("revoked")
              .resolve("certs_by_serial")
              .resolve(serial)
              .resolve(commonName + ".crt");
    }
    if (!Files.exists(certSource)) {
      throw ApiException.notFound(
          "pki_missing",
          "Revoked certificate artifacts for serial "
              + serial
              + " were not found; cannot restore "
              + commonName);
    }
    Path issuedDir = pkiDir.resolve("issued");
    try {
      Files.createDirectories(issuedDir);
      Files.copy(
          certSource, issuedDir.resolve(commonName + ".crt"), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw ApiException.internal(
          "pki_restore", "Cannot restore certificate for " + commonName + ": " + e.getMessage());
    }
    Path keySource =
        pkiDir.resolve("revoked").resolve("private_by_serial").resolve(serial + ".key");
    if (Files.exists(keySource)) {
      Path privateDir = pkiDir.resolve("private");
      try {
        Files.createDirectories(privateDir);
        Files.copy(
            keySource,
            privateDir.resolve(commonName + ".key"),
            StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw ApiException.internal(
            "pki_restore", "Cannot restore private key for " + commonName + ": " + e.getMessage());
      }
    }
  }

  /** Regenerates the CRL from the current index.txt. */
  public synchronized void genCrl() {
    requirePki();
    runEasyrsa(List.of("--batch", "gen-crl"));
  }

  /** Returns parsed index.txt entries. */
  public synchronized List<CertIndexEntry> index() {
    requirePki();
    Path indexFile = pkiDir.resolve("index.txt");
    try {
      return new IndexParser().parse(Files.readString(indexFile, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw ApiException.internal("pki_index", "Cannot read index.txt: " + e.getMessage());
    }
  }

  // ---------- file accessors ----------

  public String caCert() {
    requirePki();
    return read(pkiDir.resolve("ca.crt"), "ca.crt");
  }

  public String serverCert() {
    requirePki();
    return read(pkiDir.resolve("issued").resolve("server.crt"), "server.crt");
  }

  public String serverKey() {
    requirePki();
    return read(pkiDir.resolve("private").resolve("server.key"), "server.key");
  }

  public String taKey() {
    requirePki();
    return read(pkiDir.resolve("ta.key"), "ta.key");
  }

  public String crlPem() {
    requirePki();
    Path crl = pkiDir.resolve("crl.pem");
    if (!Files.exists(crl)) {
      genCrl();
    }
    return read(crl, "crl.pem");
  }

  public String clientCert(String cn) {
    requirePki();
    return read(pkiDir.resolve("issued").resolve(cn + ".crt"), "issued/" + cn + ".crt");
  }

  public String clientKey(String cn) {
    requirePki();
    return read(pkiDir.resolve("private").resolve(cn + ".key"), "private/" + cn + ".key");
  }

  public boolean hasClientCert(String cn) {
    return Files.exists(pkiDir.resolve("issued").resolve(cn + ".crt"));
  }

  // ---------- internals ----------

  private void requirePki() {
    if (!isInitialized()) {
      throw IndexParser.missingPki();
    }
  }

  private void runEasyrsa(List<String> args) {
    ProcessRunner.Result r = runner.run(easyrsaCommand(args), easyrsaEnvironment());
    if (!r.ok()) {
      throw ApiException.internal(
          "pki_command", "easyrsa failed: " + r.stderr() + " " + r.stdout());
    }
  }

  private List<String> easyrsaCommand(List<String> args) {
    List<String> cmd = new java.util.ArrayList<>(List.of(easyrsaBin));
    cmd.addAll(args);
    return cmd;
  }

  private Map<String, String> easyrsaEnvironment() {
    Map<String, String> env = new HashMap<>();
    env.put("EASYRSA_PKI", pkiDir.toString());
    env.put("EASYRSA_BATCH", "1");
    env.put("EASYRSA_ALGO", "rsa");
    env.put("EASYRSA_KEY_SIZE", "2048");
    env.put("EASYRSA_CERT_EXPIRE", String.valueOf(certExpireDays));
    // The CRL must stay valid at least as long as the longest issued certificate, or clients could
    // silently accept revoked certificates once the CRL lapses.
    env.put("EASYRSA_CRL_DAYS", String.valueOf(Math.max(1800, certExpireDays + 30)));
    return env;
  }

  private String read(Path path, String label) {
    if (!Files.exists(path)) {
      throw ApiException.notFound("pki_missing", "PKI artifact not found: " + label);
    }
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ApiException.internal("pki_read", "Cannot read " + label + ": " + e.getMessage());
    }
  }

  /** Removes a client cert's files (used by the restore flow in Phase 2). */
  public synchronized void deleteClientCert(String cn) {
    requirePki();
    for (String rel : List.of("issued/" + cn + ".crt", "private/" + cn + ".key")) {
      try {
        FileUtils.delete(pkiDir.resolve(rel).toFile());
      } catch (IOException ignored) {
        // best effort
      }
    }
  }
}
