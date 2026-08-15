package com.opnl.vpn.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opnl.vpn.common.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InternalTlsBootstrapTest {

  @TempDir Path tempDir;

  private ProcessRunner okRunner() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(new ProcessRunner.Result(0, "", ""));
    return runner;
  }

  @Test
  void ensureCreatesKeystorePasswordAndPlumbingArtifacts() throws IOException {
    ProcessRunner runner = okRunner();

    InternalTlsBootstrap.ensure(tempDir, runner);

    assertThat(Files.exists(tempDir.resolve(InternalTlsBootstrap.KEYSTORE_PASS_FILE))).isTrue();
    assertThat(InternalTlsBootstrap.keystorePassword(tempDir)).hasSize(32);
    // CA, server cert, keystore and truststore were each generated via a subprocess.
    verify(runner, times(5)).run(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void ensureIsIdempotentAcrossRuns() throws IOException {
    ProcessRunner runner = okRunner();
    InternalTlsBootstrap.ensure(tempDir, runner);

    verify(runner, times(5)).run(org.mockito.ArgumentMatchers.anyList());
    // The mocked subprocesses produce no artifacts; simulate the generated files so the second
    // run can short-circuit on existing material.
    Files.writeString(tempDir.resolve(InternalTlsBootstrap.CA_CERT), "ca");
    Files.writeString(tempDir.resolve(InternalTlsBootstrap.SERVER_CERT), "cert");
    Files.writeString(tempDir.resolve(InternalTlsBootstrap.KEYSTORE), "p12");
    Files.writeString(tempDir.resolve(InternalTlsBootstrap.TRUSTSTORE), "p12");

    InternalTlsBootstrap.ensure(tempDir, runner);

    // Second run short-circuits on existing artifacts: no new subprocess calls.
    verify(runner, times(5)).run(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void ensureReusesExistingKeystorePassword() throws IOException {
    Files.writeString(
        tempDir.resolve(InternalTlsBootstrap.KEYSTORE_PASS_FILE),
        "fixed-pass",
        StandardCharsets.UTF_8);
    ProcessRunner runner = okRunner();

    InternalTlsBootstrap.ensure(tempDir, runner);

    assertThat(InternalTlsBootstrap.keystorePassword(tempDir)).isEqualTo("fixed-pass");
  }

  @Test
  void ensureFailsWhenCaGenerationFails() {
    ProcessRunner runner = mock(ProcessRunner.class);
    when(runner.run(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(new ProcessRunner.Result(1, "", "openssl missing"));

    assertThatThrownBy(() -> InternalTlsBootstrap.ensure(tempDir, runner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot create internal CA");
  }

  @Test
  void ensureFailsWhenTlsDirCannotBeCreated() throws IOException {
    Path blocker = tempDir.resolve("blocker");
    Files.writeString(blocker, "file", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> InternalTlsBootstrap.ensure(blocker, okRunner()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot bootstrap internal TLS");
  }

  @Test
  void keystorePasswordThrowsWhenFileMissing() {
    assertThatThrownBy(() -> InternalTlsBootstrap.keystorePassword(tempDir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot read internal TLS keystore password");
  }

  @Test
  void tlsDirResolvesOverrideAndDefault() {
    assertThat(InternalTlsBootstrap.tlsDir("some/dir"))
        .isEqualTo(Path.of("some/dir").toAbsolutePath());
    assertThat(InternalTlsBootstrap.tlsDir(null))
        .isEqualTo(Path.of("./data/internal-tls").toAbsolutePath());
    assertThat(InternalTlsBootstrap.tlsDir("  "))
        .isEqualTo(Path.of("./data/internal-tls").toAbsolutePath());
  }

  @Test
  void ensureSkipsWhenCaAlreadyPresent() throws IOException {
    Files.createDirectories(tempDir);
    Files.writeString(tempDir.resolve(InternalTlsBootstrap.CA_CERT), "ca");
    ProcessRunner runner = okRunner();

    // CA exists, so the first subprocess step is the server key/csr generation, not the CA.
    InternalTlsBootstrap.ensure(tempDir, runner);

    verify(runner, times(4)).run(org.mockito.ArgumentMatchers.anyList());
  }
}
