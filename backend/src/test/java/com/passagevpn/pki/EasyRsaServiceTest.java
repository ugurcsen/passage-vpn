package com.passagevpn.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.common.ApiException;
import com.passagevpn.common.ProcessRunner;
import com.passagevpn.config.PassageProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the index.txt edit performed by {@link EasyRsaService#unrevokeCert}. */
class EasyRsaServiceTest {

  @TempDir Path tempDir;

  private Path pkiDir;
  private Path indexFile;
  private EasyRsaService service;

  @BeforeEach
  void setUp() throws Exception {
    pkiDir = tempDir.resolve("pki");
    Files.createDirectories(pkiDir);
    // A PKI is considered initialized once ca.crt exists.
    Files.writeString(pkiDir.resolve("ca.crt"), "dummy ca\n");
    Path easyrsaBin = tempDir.resolve("easyrsa");
    // Stub binary: Easy-RSA itself is not installed in the test environment, but
    // gen-crl/revoke must "succeed" so the service proceeds to the file assertions.
    Files.writeString(easyrsaBin, "#!/bin/sh\nexit 0\n");
    easyrsaBin.toFile().setExecutable(true);

    PassageProperties properties =
        new PassageProperties(
            tempDir.resolve("data").toString(),
            "OpenVPN Panel",
            "internal-token",
            new PassageProperties.Jwt("j".repeat(64), 900, 14),
            new PassageProperties.Auth("local", 5, 300, 300, 20, 60),
            new PassageProperties.OpenVpn(
                "127.0.0.1",
                7505,
                "vpn.example.com",
                pkiDir.toString(),
                tempDir.resolve("ccd").toString(),
                tempDir.resolve("config").toString(),
                tempDir.resolve("scripts").toString(),
                "openvpn/scripts",
                "http://backend:8080",
                easyrsaBin.toString(),
                tempDir.resolve("logs").toString(),
                "mgmt-pass",
                730,
                1194,
                1194,
                1195,
                1195));
    service = new EasyRsaService(properties, new ProcessRunner());
    indexFile = pkiDir.resolve("index.txt");
  }

  @Test
  void certExpireDaysComesFromProperties() {
    assertThat(service.certExpireDays()).isEqualTo(730);
  }

  private String indexWithRevoked(String serial, String revocationDate) {
    // index.txt row: status, expiry, revocation date, serial, filename, CN
    return "V\t270101000000Z\t\t01\tissued/alice.crt\t/CN=alice\n"
        + "R\t260101000000Z\t"
        + revocationDate
        + "\t"
        + serial
        + "\tissued/bob.crt\t/CN=bob\n";
  }

  /** Simulates the artifact layout Easy-RSA leaves behind after a revoke. */
  private void writeRevokedArtifacts(String serial, String cn) throws Exception {
    Path certsBySerial = pkiDir.resolve("certs_by_serial");
    Path revokedCerts = pkiDir.resolve("revoked").resolve("certs_by_serial");
    Path revokedKeys = pkiDir.resolve("revoked").resolve("private_by_serial");
    Path revokedReqs = pkiDir.resolve("revoked").resolve("reqs_by_serial");
    Files.createDirectories(certsBySerial);
    Files.createDirectories(revokedCerts);
    Files.createDirectories(revokedKeys);
    Files.createDirectories(revokedReqs);
    Files.writeString(
        certsBySerial.resolve(serial + ".pem"), "-----BEGIN CERTIFICATE-----\n" + cn + "\n");
    Files.writeString(
        revokedCerts.resolve(serial + ".crt"), "-----BEGIN CERTIFICATE-----\n" + cn + "\n");
    Files.writeString(
        revokedKeys.resolve(serial + ".key"), "-----BEGIN PRIVATE KEY-----\n" + cn + "\n");
    Files.writeString(
        revokedReqs.resolve(serial + ".req"), "-----BEGIN CERTIFICATE REQUEST-----\n" + cn + "\n");
  }

  @Test
  void unrevokeCertFlipsEntryToValidAndClearsRevocationDate() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));
    writeRevokedArtifacts("02", "bob");

    service.unrevokeCert("02", "bob");

    String content = Files.readString(indexFile);
    assertThat(content)
        .contains("V\t260101000000Z\t\t02\tissued/bob.crt\t/CN=bob\n")
        .doesNotContain("R\t260101000000Z\t260801000000Z\t02");
    // CRL regeneration ran (gen-crl exits 0 via the stub).
    assertThat(pkiDir.resolve("crl.pem")).doesNotExist(); // stub writes nothing, but ran
  }

  @Test
  void unrevokeCertRestoresIssuedCertAndPrivateKey() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));
    writeRevokedArtifacts("02", "bob");

    service.unrevokeCert("02", "bob");

    assertThat(Files.readString(pkiDir.resolve("issued").resolve("bob.crt")))
        .isEqualTo("-----BEGIN CERTIFICATE-----\nbob\n");
    assertThat(Files.readString(pkiDir.resolve("private").resolve("bob.key")))
        .isEqualTo("-----BEGIN PRIVATE KEY-----\nbob\n");
    // The revoked archive must not keep copies or a later revoke/rotate fails with
    // "a conflicting file exists" (Easy-RSA 3.2.x layout).
    assertThat(pkiDir.resolve("revoked").resolve("certs_by_serial").resolve("02.crt"))
        .doesNotExist();
    assertThat(pkiDir.resolve("revoked").resolve("private_by_serial").resolve("02.key"))
        .doesNotExist();
    assertThat(pkiDir.resolve("revoked").resolve("reqs_by_serial").resolve("02.req"))
        .doesNotExist();
  }

  @Test
  void unrevokeCertThrowsWhenRevokedArtifactMissing() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));

    assertThatThrownBy(() -> service.unrevokeCert("02", "bob"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_missing");
    // Nothing is flipped and nothing is restored: the index stays untouched.
    assertThat(Files.readString(indexFile)).isEqualTo(indexWithRevoked("02", "260801000000Z"));
    assertThat(pkiDir.resolve("issued").resolve("bob.crt")).doesNotExist();
  }

  @Test
  void unrevokeCertRestoresLegacyRevokedCertsBySerialLayout() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));
    Path legacy = pkiDir.resolve("revoked").resolve("certs_by_serial").resolve("02");
    Files.createDirectories(legacy);
    Files.writeString(legacy.resolve("bob.crt"), "legacy-cert");

    service.unrevokeCert("02", "bob");

    assertThat(Files.readString(pkiDir.resolve("issued").resolve("bob.crt")))
        .isEqualTo("legacy-cert");
    assertThat(legacy.resolve("bob.crt")).doesNotExist();
  }

  @Test
  void unrevokeCertKeepsOtherRowsUntouched() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));
    writeRevokedArtifacts("02", "bob");

    service.unrevokeCert("02", "bob");

    String content = Files.readString(indexFile);
    assertThat(content).contains("V\t270101000000Z\t\t01\tissued/alice.crt\t/CN=alice\n");
  }

  @Test
  void unrevokeCertThrowsWhenSerialUnknown() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));

    assertThatThrownBy(() -> service.unrevokeCert("99", "bob"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "certificate_not_found");
    assertThat(Files.readString(indexFile)).isEqualTo(indexWithRevoked("02", "260801000000Z"));
  }

  @Test
  void unrevokeCertThrowsWhenEntryIsNotRevoked() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));
    // Turn the 02 row into a valid one to simulate a non-revoked serial.
    Files.writeString(
        indexFile,
        "V\t270101000000Z\t\t01\tissued/alice.crt\t/CN=alice\n"
            + "V\t260101000000Z\t\t02\tissued/bob.crt\t/CN=bob\n");

    assertThatThrownBy(() -> service.unrevokeCert("02", "bob"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "not_revoked");
  }

  @Test
  void unrevokeCertMatchesByCommonNameWhenSerialMissing() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));
    writeRevokedArtifacts("02", "bob");

    service.unrevokeCert(null, "bob");

    String content = Files.readString(indexFile);
    assertThat(content)
        .contains("V\t260101000000Z\t\t02\tissued/bob.crt\t/CN=bob\n")
        .doesNotContain("R\t260101000000Z\t260801000000Z\t02");
    // The serial is taken from the matching index row so artifacts are still restored.
    assertThat(Files.readString(pkiDir.resolve("issued").resolve("bob.crt")))
        .isEqualTo("-----BEGIN CERTIFICATE-----\nbob\n");
  }

  @Test
  void unrevokeCertThrowsWhenSerialAndCommonNameBothMissing() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));

    assertThatThrownBy(() -> service.unrevokeCert(null, null))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_index");
    assertThat(Files.readString(indexFile)).isEqualTo(indexWithRevoked("02", "260801000000Z"));
  }

  // ---------- PKI state ----------

  @Test
  void isInitializedReflectsCaCrtPresence() throws Exception {
    Path dir = tempDir.resolve("fresh");
    Files.createDirectories(dir);
    EasyRsaService fresh = serviceWithRunner(dir, mock(ProcessRunner.class));
    assertThat(fresh.isInitialized()).isFalse();

    Files.writeString(dir.resolve("ca.crt"), "dummy");
    assertThat(fresh.isInitialized()).isTrue();
  }

  @Test
  void pkiDirIsAbsolute() {
    Path dir = tempDir.resolve("rel").toAbsolutePath();
    assertThat(serviceWithRunner(dir, mock(ProcessRunner.class)).pkiDir()).isEqualTo(dir);
  }

  // ---------- init ----------

  @Test
  void initPkiSkipsWhenAlreadyInitialized() {
    ProcessRunner runner = mock(ProcessRunner.class);
    serviceWithRunner(pkiDir, runner).initPki();

    verify(runner, never()).run(anyList(), anyMap());
  }

  @Test
  void initPkiRunsInitAndBuildCaAndGeneratesTaKey() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(anyList(), anyMap())).thenReturn(ok());
    when(runner.run(anyList(), anyMap(), any())).thenReturn(ok());
    EasyRsaService s = serviceWithRunner(tempDir.resolve("fresh-init"), runner);

    s.initPki();

    verify(runner, times(2)).run(anyList(), anyMap());
    verify(runner).run(anyList(), anyMap(), any());
  }

  @Test
  void initPkiThrowsWhenEasyrsaStepFails() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(anyList(), anyMap())).thenReturn(fail("boom"));
    EasyRsaService s = serviceWithRunner(tempDir.resolve("fresh-init-fail"), runner);

    assertThatThrownBy(s::initPki)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_command");
  }

  @Test
  void initPkiThrowsWhenTaKeyGenerationFails() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(anyList(), anyMap())).thenReturn(ok());
    when(runner.run(anyList(), anyMap(), any())).thenReturn(fail("boom"));
    EasyRsaService s = serviceWithRunner(tempDir.resolve("fresh-genkey-fail"), runner);

    assertThatThrownBy(s::initPki)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_init");
  }

  // ---------- server cert ----------

  @Test
  void buildServerCertCopiesExistingFilesWithoutRunning() throws Exception {
    ProcessRunner runner = mock(ProcessRunner.class);
    Files.createDirectories(pkiDir.resolve("issued"));
    Files.createDirectories(pkiDir.resolve("private"));
    Files.writeString(pkiDir.resolve("issued/server.crt"), "crt body");
    Files.writeString(pkiDir.resolve("private/server.key"), "key body");

    serviceWithRunner(pkiDir, runner).buildServerCert("server");

    verify(runner, never()).run(anyList(), anyMap());
    assertThat(Files.readString(pkiDir.resolve("server.crt"))).isEqualTo("crt body");
    assertThat(Files.readString(pkiDir.resolve("server.key"))).isEqualTo("key body");
  }

  @Test
  void buildServerCertRunsEasyrsaWhenMissingAndCopiesToRoot() throws Exception {
    Path bin = tempDir.resolve("easyrsa-server");
    Files.writeString(
        bin,
        "#!/bin/sh\n"
            + "mkdir -p \"$EASYRSA_PKI/issued\" \"$EASYRSA_PKI/private\"\n"
            + "echo crt > \"$EASYRSA_PKI/issued/server.crt\"\n"
            + "echo key > \"$EASYRSA_PKI/private/server.key\"\n"
            + "exit 0\n");
    bin.toFile().setExecutable(true);
    Path dir = tempDir.resolve("server-fresh");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("ca.crt"), "ca");
    EasyRsaService s = serviceWith(dir, bin.toString(), new ProcessRunner());

    s.buildServerCert("server");

    assertThat(dir.resolve("server.crt")).exists();
    assertThat(dir.resolve("server.key")).exists();
  }

  @Test
  void buildServerCertThrowsWhenPkiMissing() {
    ProcessRunner runner = mock(ProcessRunner.class);
    EasyRsaService s = serviceWithRunner(tempDir.resolve("no-pki"), runner);

    assertThatThrownBy(() -> s.buildServerCert("server"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_not_initialized");
  }

  // ---------- client certs ----------

  @Test
  void issueClientCertRunsBuildClientFull() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(anyList(), anyMap())).thenReturn(ok());
    EasyRsaService s = serviceWithRunner(pkiDir, runner);

    s.issueClientCert("alice");

    verify(runner).run(anyList(), anyMap());
  }

  @Test
  void issueClientCertThrowsWhenPkiMissing() {
    ProcessRunner runner = mock(ProcessRunner.class);
    EasyRsaService s = serviceWithRunner(tempDir.resolve("no-pki"), runner);

    assertThatThrownBy(() -> s.issueClientCert("alice"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_not_initialized");
  }

  @Test
  void revokeCertRunsRevokeAndGenCrl() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(anyList(), anyMap())).thenReturn(ok());
    EasyRsaService s = serviceWithRunner(pkiDir, runner);

    s.revokeCert("alice");

    verify(runner, times(2)).run(anyList(), anyMap());
  }

  @Test
  void genCrlThrowsWhenEasyrsaFails() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(anyList(), anyMap())).thenReturn(fail("boom"));
    EasyRsaService s = serviceWithRunner(pkiDir, runner);

    assertThatThrownBy(s::genCrl)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_command");
  }

  @Test
  void hasClientCertReflectsIssuedCertificatePresence() throws Exception {
    assertThat(service.hasClientCert("alice")).isFalse();
    Files.createDirectories(pkiDir.resolve("issued"));
    Files.writeString(pkiDir.resolve("issued/alice.crt"), "x");
    assertThat(service.hasClientCert("alice")).isTrue();
  }

  // ---------- index ----------

  @Test
  void indexParsesEntries() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));

    List<CertIndexEntry> entries = service.index();

    assertThat(entries).hasSize(2);
  }

  @Test
  void indexThrowsWhenIndexFileMissing() {
    assertThatThrownBy(service::index)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_index");
  }

  @Test
  void unrevokeCertThrowsWhenIndexUnreadable() throws Exception {
    Files.createDirectories(indexFile);

    assertThatThrownBy(() -> service.unrevokeCert("02", "bob"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_index");
  }

  // ---------- file accessors ----------

  @Test
  void fileAccessorsReadArtifacts() throws Exception {
    Files.createDirectories(pkiDir.resolve("issued"));
    Files.createDirectories(pkiDir.resolve("private"));
    Files.writeString(pkiDir.resolve("ca.crt"), "ca content");
    Files.writeString(pkiDir.resolve("issued/server.crt"), "server crt");
    Files.writeString(pkiDir.resolve("private/server.key"), "server key");
    Files.writeString(pkiDir.resolve("ta.key"), "ta content");
    Files.writeString(pkiDir.resolve("issued/alice.crt"), "alice crt");
    Files.writeString(pkiDir.resolve("private/alice.key"), "alice key");

    assertThat(service.caCert()).isEqualTo("ca content");
    assertThat(service.serverCert()).isEqualTo("server crt");
    assertThat(service.serverKey()).isEqualTo("server key");
    assertThat(service.taKey()).isEqualTo("ta content");
    assertThat(service.clientCert("alice")).isEqualTo("alice crt");
    assertThat(service.clientKey("alice")).isEqualTo("alice key");
  }

  @Test
  void fileAccessorsThrowWhenPkiNotInitialized() throws Exception {
    Files.deleteIfExists(pkiDir.resolve("ca.crt"));

    assertThatThrownBy(service::caCert)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_not_initialized");
    assertThatThrownBy(service::serverCert)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_not_initialized");
    assertThatThrownBy(service::serverKey)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_not_initialized");
    assertThatThrownBy(service::taKey)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_not_initialized");
    assertThatThrownBy(() -> service.clientCert("alice"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_not_initialized");
    assertThatThrownBy(() -> service.clientKey("alice"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_not_initialized");
  }

  @Test
  void fileAccessorsThrowPkiMissingWhenArtifactAbsent() throws Exception {
    Files.createDirectories(pkiDir);
    Files.writeString(pkiDir.resolve("ca.crt"), "ca content");

    assertThatThrownBy(service::serverCert)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_missing");
    assertThatThrownBy(service::serverKey)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_missing");
    assertThatThrownBy(service::taKey)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_missing");
    assertThatThrownBy(() -> service.clientCert("alice"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_missing");
    assertThatThrownBy(() -> service.clientKey("alice"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_missing");
  }

  @Test
  void crlPemRegeneratesAndReturnsContent() throws Exception {
    Path bin = tempDir.resolve("easyrsa-crl");
    Files.writeString(bin, "#!/bin/sh\necho \"CRL CONTENT\" > \"$EASYRSA_PKI/crl.pem\"\nexit 0\n");
    bin.toFile().setExecutable(true);
    Path dir = tempDir.resolve("crl-test");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("ca.crt"), "ca");
    EasyRsaService s = serviceWith(dir, bin.toString(), new ProcessRunner());

    assertThat(s.crlPem()).isEqualTo("CRL CONTENT\n");
    assertThat(dir.resolve("crl.pem")).exists();
  }

  @Test
  void crlPemThrowsWhenRegenerationWritesNothing() {
    assertThatThrownBy(service::crlPem)
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_missing");
  }

  @Test
  void deleteClientCertRemovesIssuedAndPrivateFiles() throws Exception {
    Files.createDirectories(pkiDir.resolve("issued"));
    Files.createDirectories(pkiDir.resolve("private"));
    Files.writeString(pkiDir.resolve("issued/alice.crt"), "x");
    Files.writeString(pkiDir.resolve("private/alice.key"), "y");

    service.deleteClientCert("alice");

    assertThat(pkiDir.resolve("issued/alice.crt")).doesNotExist();
    assertThat(pkiDir.resolve("private/alice.key")).doesNotExist();
  }

  // ---------- helpers ----------

  private EasyRsaService serviceWith(Path pkiDir, String easyrsaBin, ProcessRunner runner) {
    PassageProperties properties =
        new PassageProperties(
            tempDir.resolve("data").toString(),
            "OpenVPN Panel",
            "internal-token",
            new PassageProperties.Jwt("j".repeat(64), 900, 14),
            new PassageProperties.Auth("local", 5, 300, 300, 20, 60),
            new PassageProperties.OpenVpn(
                "127.0.0.1",
                7505,
                "vpn.example.com",
                pkiDir.toString(),
                tempDir.resolve("ccd").toString(),
                tempDir.resolve("config").toString(),
                tempDir.resolve("scripts").toString(),
                "openvpn/scripts",
                "http://backend:8080",
                easyrsaBin,
                tempDir.resolve("logs").toString(),
                "mgmt-pass",
                730,
                1194,
                1194,
                1195,
                1195));
    return new EasyRsaService(properties, runner);
  }

  private EasyRsaService serviceWithRunner(Path pkiDir, ProcessRunner runner) {
    return serviceWith(pkiDir, "/usr/bin/easyrsa", runner);
  }

  private ProcessRunner.Result ok() {
    return new ProcessRunner.Result(0, "", "");
  }

  private ProcessRunner.Result fail(String stderr) {
    return new ProcessRunner.Result(1, "", stderr);
  }
}
