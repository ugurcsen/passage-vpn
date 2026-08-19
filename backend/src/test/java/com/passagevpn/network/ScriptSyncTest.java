package com.passagevpn.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.passagevpn.config.PassageProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.ApplicationArguments;

class ScriptSyncTest {

  @TempDir Path tempDir;

  private ScriptSync sync(String scriptsSrcDir, String scriptsDir, String token) {
    PassageProperties.Jwt jwt = new PassageProperties.Jwt("secret-secret-secret", 300, 14);
    PassageProperties.Auth auth = new PassageProperties.Auth("local", 5, 300, 900, 30, 60, null);
    PassageProperties.OpenVpn openvpn =
        new PassageProperties.OpenVpn(
            "127.0.0.1",
            7505,
            "vpn.example.com",
            "/pki",
            "/ccd",
            "/config",
            scriptsDir,
            scriptsSrcDir,
            "http://localhost",
            "easyrsa",
            "/logs",
            "mgmt-secret",
            730,
            1194,
            1194,
            1195,
            1195);
    PassageProperties properties =
        new PassageProperties("./data", "OpenVPN Panel", token, jwt, auth, openvpn);
    return new ScriptSync(properties);
  }

  @Test
  void syncRendersScriptsWithTokenSubstitution() throws IOException {
    Path src = tempDir.resolve("src");
    Path dest = tempDir.resolve("dest");
    Files.createDirectories(src);
    Files.writeString(
        src.resolve("verify-user-pass.sh"),
        "curl -H 'Authorization: __INTERNAL_TOKEN__'",
        StandardCharsets.UTF_8);
    Files.writeString(
        src.resolve("client-connect.py"), "token=__INTERNAL_TOKEN__", StandardCharsets.UTF_8);
    Files.writeString(src.resolve("README.md"), "not a script", StandardCharsets.UTF_8);

    sync(src.toString(), dest.toString(), "secret-token").sync();

    assertThat(dest.resolve("verify-user-pass.sh"))
        .exists()
        .hasContent("curl -H 'Authorization: secret-token'");
    assertThat(dest.resolve("client-connect.py")).exists().hasContent("token=secret-token");
    assertThat(dest.resolve("README.md")).doesNotExist();
  }

  @Test
  void syncCreatesDestinationDirectoryAndMarksScriptsExecutable() throws IOException {
    Path src = tempDir.resolve("src");
    Path dest = tempDir.resolve("nested").resolve("dest");
    Files.createDirectories(src);
    Files.writeString(src.resolve("a.sh"), "echo __INTERNAL_TOKEN__", StandardCharsets.UTF_8);

    sync(src.toString(), dest.toString(), "tok").sync();

    assertThat(dest).isDirectory();
    assertThat(Files.isExecutable(dest.resolve("a.sh"))).isTrue();
    assertThat(dest.resolve("a.sh")).hasContent("echo tok");
  }

  @Test
  void syncReplacesEveryTokenOccurrence() throws IOException {
    Path src = tempDir.resolve("src");
    Files.createDirectories(src);
    Files.writeString(
        src.resolve("a.sh"), "__INTERNAL_TOKEN__ and __INTERNAL_TOKEN__", StandardCharsets.UTF_8);

    sync(src.toString(), tempDir.resolve("dest").toString(), "tok").sync();

    assertThat(tempDir.resolve("dest").resolve("a.sh")).hasContent("tok and tok");
  }

  @Test
  void syncWithNullTokenStripsPlaceholders() throws IOException {
    Path src = tempDir.resolve("src");
    Files.createDirectories(src);
    Files.writeString(src.resolve("a.sh"), "key=__INTERNAL_TOKEN__", StandardCharsets.UTF_8);

    sync(src.toString(), tempDir.resolve("dest").toString(), null).sync();

    assertThat(tempDir.resolve("dest").resolve("a.sh")).hasContent("key=");
  }

  @Test
  void syncSkipsWhenSourceDirMissing() {
    Path src = tempDir.resolve("missing");
    Path dest = tempDir.resolve("dest");

    sync(src.toString(), dest.toString(), "tok").sync();

    assertThat(dest).isDirectory();
    try (var files = Files.list(dest)) {
      assertThat(files).isEmpty();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void runDelegatesToSync() throws IOException {
    Path src = tempDir.resolve("src");
    Path dest = tempDir.resolve("dest");
    Files.createDirectories(src);
    Files.writeString(src.resolve("a.sh"), "x", StandardCharsets.UTF_8);

    sync(src.toString(), dest.toString(), "tok").run(Mockito.mock(ApplicationArguments.class));

    assertThat(dest.resolve("a.sh")).exists();
  }
}
