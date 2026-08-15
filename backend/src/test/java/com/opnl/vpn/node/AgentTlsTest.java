package com.opnl.vpn.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ProcessRunner;
import com.opnl.vpn.config.AgentProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTlsTest {

  @TempDir Path tempDir;

  private AgentTls agent(String ca, String cert, String key, ProcessRunner runner) {
    AgentProperties properties =
        new AgentProperties(null, null, null, 0, null, null, 0, 0, ca, cert, key);
    return new AgentTls(properties, runner);
  }

  private void runCommand(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new IllegalStateException(
          "setup command failed: " + String.join(" ", command) + ": " + new String(output));
    }
  }

  @Test
  void configuredIsTrueOnlyWhenAllMaterialPresent() {
    assertThat(agent("ca.pem", "cert.pem", "key.pem", mock(ProcessRunner.class)).configured())
        .isTrue();
    assertThat(agent(null, "cert.pem", "key.pem", mock(ProcessRunner.class)).configured())
        .isFalse();
    assertThat(agent("ca.pem", "   ", "key.pem", mock(ProcessRunner.class)).configured()).isFalse();
    assertThat(agent("ca.pem", "cert.pem", null, mock(ProcessRunner.class)).configured()).isFalse();
  }

  @Test
  void sslContextBuildsFromPemMaterialAndCachesResult() throws Exception {
    Path ca = tempDir.resolve("ca.pem");
    Path cert = tempDir.resolve("cert.pem");
    Path key = tempDir.resolve("key.pem");
    runCommand(
        "openssl",
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-keyout",
        key.toString(),
        "-out",
        cert.toString(),
        "-days",
        "3650",
        "-nodes",
        "-subj",
        "/CN=agent");
    Files.copy(cert, ca);

    AgentTls agent = agent(ca.toString(), cert.toString(), key.toString(), new ProcessRunner());

    SSLContext first = agent.sslContext();
    SSLContext second = agent.sslContext();

    assertThat(first.getProtocol()).isEqualTo("TLS");
    assertThat(second).isSameAs(first);
  }

  @Test
  void sslContextFailsWhenOpensslExportFails() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(anyList())).thenReturn(new ProcessRunner.Result(1, "", "openssl missing"));
    AgentTls agent = agent("ca.pem", "cert.pem", "key.pem", runner);

    assertThatThrownBy(agent::sslContext)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot build agent PKCS12 bundle");
  }

  @Test
  void sslContextFailsWhenPkcs12BundleCannotBeLoaded() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(anyList())).thenReturn(new ProcessRunner.Result(0, "", ""));
    AgentTls agent = agent("ca.pem", "cert.pem", "key.pem", runner);

    assertThatThrownBy(agent::sslContext)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot load agent PKCS12 bundle");
  }

  @Test
  void sslContextFailsFastWhenMaterialPathsAreMissing() {
    AgentTls agent = agent(null, null, null, mock(ProcessRunner.class));

    assertThatThrownBy(agent::sslContext).isInstanceOf(NullPointerException.class);
  }
}
