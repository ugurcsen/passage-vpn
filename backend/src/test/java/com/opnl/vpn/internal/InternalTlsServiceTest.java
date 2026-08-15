package com.opnl.vpn.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ProcessRunner;
import com.opnl.vpn.config.InternalProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InternalTlsServiceTest {

  @TempDir Path tempDir;

  private ProcessRunner runner;
  private InternalTlsService service;

  @BeforeEach
  void setUp() throws IOException {
    runner = mock(ProcessRunner.class);
    when(runner.run(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(new ProcessRunner.Result(0, "", ""));
    service = new InternalTlsService(new InternalProperties(8443, tempDir.toString()), runner);
  }

  private void createIssuedMaterial(String nodeName) throws IOException {
    Path agent = service.agentDir(nodeName);
    Files.createDirectories(agent);
    Files.writeString(agent.resolve("client.crt"), "CERT-CONTENT", StandardCharsets.UTF_8);
    Files.writeString(agent.resolve("client.key"), "KEY-CONTENT", StandardCharsets.UTF_8);
    Files.writeString(agent.resolve("client.p12"), "P12-CONTENT", StandardCharsets.UTF_8);
  }

  @Test
  void issueAgentCertBuildsMaterialAndKeepsGivenPassword() throws IOException {
    Files.writeString(service.tlsDir().resolve(InternalTlsBootstrap.CA_CERT), "CA-CONTENT");
    createIssuedMaterial("node1");

    InternalTlsService.AgentCertificate result = service.issueAgentCert("node1", "s3cret");

    assertThat(result.nodeName()).isEqualTo("node1");
    assertThat(result.caCertPem()).isEqualTo("CA-CONTENT");
    assertThat(result.certPem()).isEqualTo("CERT-CONTENT");
    assertThat(result.keyPem()).isEqualTo("KEY-CONTENT");
    assertThat(result.pkcs12()).isEqualTo("P12-CONTENT".getBytes(StandardCharsets.UTF_8));
    assertThat(result.password()).isEqualTo("s3cret");
    assertThat(
            Files.readString(
                service.agentDir("node1").resolve("client.pass"), StandardCharsets.UTF_8))
        .isEqualTo("s3cret");
    assertThat(Files.exists(service.agentDir("node1").resolve("client-ext.cnf"))).isTrue();
  }

  @Test
  void issueAgentCertGeneratesRandomPasswordWhenBlank() throws IOException {
    Files.writeString(service.tlsDir().resolve(InternalTlsBootstrap.CA_CERT), "CA-CONTENT");
    createIssuedMaterial("node2");

    InternalTlsService.AgentCertificate result = service.issueAgentCert("node2", null);

    assertThat(result.password()).hasSize(32);
    assertThat(
            Files.readString(
                service.agentDir("node2").resolve("client.pass"), StandardCharsets.UTF_8))
        .isEqualTo(result.password());
  }

  @Test
  void issueAgentCertThrowsWhenCsrGenerationFails() {
    when(runner.run(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(new ProcessRunner.Result(1, "", "no openssl"));

    assertThatThrownBy(() -> service.issueAgentCert("node1", "pw"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no openssl");
  }

  @Test
  void issueAgentCertThrowsWhenSigningFails() {
    when(runner.run(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(new ProcessRunner.Result(0, "", ""))
        .thenReturn(new ProcessRunner.Result(1, "", "bad csr"));

    assertThatThrownBy(() -> service.issueAgentCert("node1", "pw"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bad csr");
  }

  @Test
  void issueAgentCertThrowsWhenPkcs12ExportFails() {
    when(runner.run(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(new ProcessRunner.Result(0, "", ""))
        .thenReturn(new ProcessRunner.Result(0, "", ""))
        .thenReturn(new ProcessRunner.Result(1, "", "pkcs12 failed"));

    assertThatThrownBy(() -> service.issueAgentCert("node1", "pw"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pkcs12 failed");
  }

  @Test
  void issueAgentCertFailsWhenAgentDirCannotBeCreated() throws IOException {
    Path blocker = tempDir.resolve("blocker");
    Files.writeString(blocker, "file", StandardCharsets.UTF_8);
    InternalTlsService blocked =
        new InternalTlsService(new InternalProperties(8443, blocker.toString()), runner);

    assertThatThrownBy(() -> blocked.issueAgentCert("node1", "pw"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot create agent tls dir");
  }

  @Test
  void issueAgentCertThrowsWhenMaterialCannotBeRead() throws IOException {
    Path agent = service.agentDir("node1");
    Files.createDirectories(agent);
    Files.writeString(agent.resolve("client.key"), "KEY", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> service.issueAgentCert("node1", "pw"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot read issued agent material");
  }

  @Test
  void agentDirSanitizesNodeName() {
    assertThat(service.agentDir("a b/c:ok!").getFileName().toString()).isEqualTo("a_b_c_ok_");
    assertThat(service.agentDir("plain").getFileName().toString()).isEqualTo("plain");
  }

  @Test
  void caCertPemReadsCaCertificate() throws IOException {
    Files.writeString(service.tlsDir().resolve(InternalTlsBootstrap.CA_CERT), "CA-PEM");
    assertThat(service.caCertPem()).isEqualTo("CA-PEM");
  }
}
