package com.opnl.vpn.network;

import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Writes generated daemon configs into the shared config volume. */
@Slf4j
@Service
public class ConfigWriter {

  private final Path configDir;
  private final Path logDir;

  public ConfigWriter(OpnlProperties properties) {
    this.configDir = Path.of(properties.openvpn().configDir()).toAbsolutePath();
    // OpenVPN writes its logs inside the container; keep the log dir next to
    // the config dir for easy inspection.
    this.logDir =
        Path.of(properties.openvpn().configDir()).toAbsolutePath().getParent().resolve("logs");
  }

  /**
   * Renders and writes daemon-<index>.conf for the given config. Removes the previous config file
   * of the same daemon index first.
   */
  public synchronized void writeDaemon(
      ServerConfig config, ServerConfigGenerator generator, OpnlProperties props) {
    try {
      Files.createDirectories(configDir);
      Files.createDirectories(logDir);
      // Client-config-dir must exist for `ccd-exclusive` to work.
      Files.createDirectories(Path.of(props.openvpn().ccdDir()));
    } catch (IOException e) {
      throw ApiException.internal("config_write", "Cannot create config dirs: " + e.getMessage());
    }

    String rendered =
        generator.render(
            config,
            props.openvpn().pkiDir(),
            props.openvpn().ccdDir(),
            props.openvpn().scriptsDir(),
            logDir.toString());

    Path file = configDir.resolve("daemon-" + config.daemonIndex() + ".conf");
    try {
      Files.writeString(file, rendered, StandardCharsets.UTF_8);
      log.info("Wrote {}", file);
    } catch (IOException e) {
      throw ApiException.internal("config_write", "Cannot write " + file + ": " + e.getMessage());
    }
  }

  /** Removes a daemon config file (used when disabling a daemon). */
  public synchronized void removeDaemon(int daemonIndex) {
    Path file = configDir.resolve("daemon-" + daemonIndex + ".conf");
    try {
      Files.deleteIfExists(file);
      log.info("Removed {}", file);
    } catch (IOException e) {
      throw ApiException.internal("config_write", "Cannot remove " + file + ": " + e.getMessage());
    }
  }
}
