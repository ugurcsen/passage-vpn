package com.opnl.vpn.pki;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.common.ProcessRunner;
import com.opnl.vpn.config.OpnlProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

  public EasyRsaService(OpnlProperties properties, ProcessRunner runner) {
    this.pkiDir = Path.of(properties.openvpn().pkiDir()).toAbsolutePath();
    this.runner = runner;
    this.easyrsaBin = properties.openvpn().easyrsaBin();
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
   * @throws ApiException {@code certificate_not_found} when no revoked entry carries the serial,
   *     {@code not_revoked} when the entry exists but is not in the revoked state
   */
  public synchronized void unrevokeCert(String serial) {
    requirePki();
    Path indexFile = pkiDir.resolve("index.txt");
    List<String> lines;
    try {
      lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ApiException.internal("pki_index", "Cannot read index.txt: " + e.getMessage());
    }
    java.util.ArrayList<String> updated = new java.util.ArrayList<>(lines.size());
    boolean restored = false;
    boolean found = false;
    for (String line : lines) {
      String[] parts = line.split("\\t", -1);
      if (parts.length >= 6 && "R".equals(parts[0]) && serial.equals(parts[3])) {
        found = true;
        // index.txt row: status, expiry, revocation date, serial, filename, CN
        updated.add("V\t" + parts[1] + "\t\t" + parts[3] + "\t" + parts[4] + "\t" + parts[5]);
        restored = true;
        continue;
      }
      if (parts.length >= 4 && !"R".equals(parts[0]) && serial.equals(parts[3])) {
        found = true;
      }
      updated.add(line);
    }
    if (!found) {
      throw ApiException.notFound(
          "certificate_not_found", "No certificate with serial " + serial + " in the PKI index");
    }
    if (!restored) {
      throw ApiException.conflict(
          "not_revoked", "Certificate with serial " + serial + " is not revoked");
    }
    try {
      Files.write(indexFile, updated, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ApiException.internal("pki_index", "Cannot write index.txt: " + e.getMessage());
    }
    genCrl();
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
    env.put("EASYRSA_CERT_EXPIRE", "3650");
    env.put("EASYRSA_CRL_DAYS", "1800");
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
