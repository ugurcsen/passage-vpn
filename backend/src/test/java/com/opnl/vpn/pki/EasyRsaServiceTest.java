package com.opnl.vpn.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.common.ProcessRunner;
import com.opnl.vpn.config.OpnlProperties;
import java.nio.file.Files;
import java.nio.file.Path;
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

    OpnlProperties properties =
        new OpnlProperties(
            tempDir.resolve("data").toString(),
            "OpenVPN Panel",
            "internal-token",
            new OpnlProperties.Jwt("j".repeat(64), 900, 14),
            new OpnlProperties.Auth("local", 5, 300, 300, 20, 60),
            new OpnlProperties.OpenVpn(
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
                "mgmt-pass"));
    service = new EasyRsaService(properties, new ProcessRunner());
    indexFile = pkiDir.resolve("index.txt");
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

  @Test
  void unrevokeCertFlipsEntryToValidAndClearsRevocationDate() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));

    service.unrevokeCert("02", "bob");

    String content = Files.readString(indexFile);
    assertThat(content)
        .contains("V\t260101000000Z\t\t02\tissued/bob.crt\t/CN=bob\n")
        .doesNotContain("R\t260101000000Z\t260801000000Z\t02");
    // CRL regeneration ran (gen-crl exits 0 via the stub).
    assertThat(pkiDir.resolve("crl.pem")).doesNotExist(); // stub writes nothing, but ran
  }

  @Test
  void unrevokeCertKeepsOtherRowsUntouched() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));

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

    service.unrevokeCert(null, "bob");

    String content = Files.readString(indexFile);
    assertThat(content)
        .contains("V\t260101000000Z\t\t02\tissued/bob.crt\t/CN=bob\n")
        .doesNotContain("R\t260101000000Z\t260801000000Z\t02");
  }

  @Test
  void unrevokeCertThrowsWhenSerialAndCommonNameBothMissing() throws Exception {
    Files.writeString(indexFile, indexWithRevoked("02", "260801000000Z"));

    assertThatThrownBy(() -> service.unrevokeCert(null, null))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "pki_index");
    assertThat(Files.readString(indexFile)).isEqualTo(indexWithRevoked("02", "260801000000Z"));
  }
}
