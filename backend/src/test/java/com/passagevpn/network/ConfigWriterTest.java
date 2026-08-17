package com.passagevpn.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.passagevpn.common.ApiException;
import com.passagevpn.config.PassageProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies daemon config rendering writes the management password file and keeps it in sync. */
class ConfigWriterTest {

  @TempDir Path tempDir;

  private PassageProperties props(String mgmtPassword) {
    PassageProperties.Jwt jwt = new PassageProperties.Jwt("secret-secret-secret-secret", 300, 14);
    PassageProperties.Auth auth = new PassageProperties.Auth("local", 5, 300, 900, 30, 60);
    PassageProperties.OpenVpn openvpn =
        new PassageProperties.OpenVpn(
            "127.0.0.1",
            7505,
            "vpn.example.com",
            tempDir.resolve("pki").toString(),
            tempDir.resolve("ccd").toString(),
            tempDir.resolve("config").toString(),
            tempDir.resolve("scripts").toString(),
            tempDir.resolve("scripts").toString(),
            "http://localhost",
            "easyrsa",
            tempDir.resolve("logs").toString(),
            mgmtPassword,
            730,
            1194,
            1194,
            1195,
            1195);
    return new PassageProperties(tempDir.toString(), "OpenVPN Panel", "token", jwt, auth, openvpn);
  }

  @Test
  void writeDaemonWritesConfigAndManagementPasswordFile() throws Exception {
    PassageProperties props = props("mgmt-secret");
    ConfigWriter writer = new ConfigWriter(props);

    writer.writeDaemon(
        ServerConfig.defaults(), new ServerConfigGenerator(new ObjectMapper()), props, "nat");

    Path conf = tempDir.resolve("config/daemon-0.conf");
    Path pass = tempDir.resolve("config/daemon-0.mgmt-pass");
    assertThat(conf).exists();
    assertThat(pass).exists();
    assertThat(Files.readString(pass, StandardCharsets.UTF_8)).isEqualTo("mgmt-secret");
    assertThat(Files.readString(conf, StandardCharsets.UTF_8))
        .contains("management 0.0.0.0 7505 " + pass);
  }

  @Test
  void removeDaemonDeletesConfigAndPasswordFile() throws Exception {
    PassageProperties props = props("mgmt-secret");
    ConfigWriter writer = new ConfigWriter(props);
    writer.writeDaemon(
        ServerConfig.defaults(), new ServerConfigGenerator(new ObjectMapper()), props, "nat");

    writer.removeDaemon(0);

    assertThat(tempDir.resolve("config/daemon-0.conf")).doesNotExist();
    assertThat(tempDir.resolve("config/daemon-0.mgmt-pass")).doesNotExist();
  }

  @Test
  void renderDaemonReturnsConfAndPasswordWithoutWriting() {
    PassageProperties props = props("mgmt-secret");
    ConfigWriter writer = new ConfigWriter(props);

    ConfigWriter.DaemonRender render =
        writer.renderDaemon(
            ServerConfig.defaults(), new ServerConfigGenerator(new ObjectMapper()), props, "nat");

    assertThat(render.daemonIndex()).isZero();
    assertThat(render.mgmtPassword()).isEqualTo("mgmt-secret");
    assertThat(render.conf())
        .contains("management 0.0.0.0 7505 " + tempDir.resolve("config/daemon-0.mgmt-pass"));
    assertThat(tempDir.resolve("config/daemon-0.conf")).doesNotExist();
  }

  @Test
  void writeDaemonRejectsMissingManagementPassword() {
    PassageProperties props = props(null);
    ConfigWriter writer = new ConfigWriter(props);

    assertThatThrownBy(
            () ->
                writer.writeDaemon(
                    ServerConfig.defaults(),
                    new ServerConfigGenerator(new ObjectMapper()),
                    props,
                    "nat"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("mgmt_password_missing"))
        .hasMessageContaining("PASSAGE_OPENVPN_MGMT_PASSWORD");
  }
}
