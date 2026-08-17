package com.passagevpn.network;

import com.passagevpn.config.PassageProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Renders the OpenVPN helper scripts (verify-user-pass, client-connect, ...) into the shared config
 * volume with the internal auth token substituted.
 *
 * <p>Scripts are read from {@code PASSAGE_SCRIPTS_SRC_DIR} (bundled into the image or the repo in
 * dev) and written to {@code PASSAGE_CONFIG_DIR}/scripts. This keeps secrets out of the repository
 * while allowing the container to mount scripts read-only.
 *
 * <p>Disabled on node agents: a remote gateway gets its scripts via the config-bundle pull,
 * rendered by the central backend, so the agent never self-writes into the shared volume.
 */
@Slf4j
@Component
@Profile("!agent")
public class ScriptSync implements ApplicationRunner {

  private final Path srcDir;
  private final Path destDir;
  private final String internalToken;

  public ScriptSync(PassageProperties properties) {
    this.srcDir = Path.of(properties.openvpn().scriptsSrcDir());
    this.destDir = Path.of(properties.openvpn().scriptsDir());
    this.internalToken = properties.internalToken();
  }

  @Override
  public void run(ApplicationArguments args) {
    sync();
  }

  public synchronized void sync() {
    try {
      Files.createDirectories(destDir);
    } catch (IOException e) {
      log.warn("Cannot create scripts dir {}: {}", destDir, e.getMessage());
      return;
    }
    if (!Files.isDirectory(srcDir)) {
      log.warn("Scripts source dir {} not found; skipping script sync", srcDir);
      return;
    }
    try (Stream<Path> files = Files.list(srcDir)) {
      files.filter(p -> isSyncedScript(p.toString())).forEach(this::render);
      log.info("Synced OpenVPN scripts from {} to {}", srcDir, destDir);
    } catch (IOException e) {
      log.warn("Failed to sync scripts: {}", e.getMessage());
    }
  }

  private static boolean isSyncedScript(String fileName) {
    return fileName.endsWith(".sh") || fileName.endsWith(".py");
  }

  private void render(Path src) {
    try {
      String content = Files.readString(src, StandardCharsets.UTF_8);
      content = content.replace("__INTERNAL_TOKEN__", internalToken == null ? "" : internalToken);
      Path dest = destDir.resolve(src.getFileName().toString());
      Files.writeString(dest, content, StandardCharsets.UTF_8);
      if (!dest.toFile().setExecutable(true, true)) {
        log.warn("Cannot mark {} executable", dest);
      }
    } catch (IOException e) {
      log.warn("Failed to render script {}: {}", src, e.getMessage());
    }
  }
}
