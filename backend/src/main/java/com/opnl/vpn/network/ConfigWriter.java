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
    this.logDir = Path.of(properties.openvpn().logDir()).toAbsolutePath();
  }

  /**
   * Renders and writes daemon-<index>.conf for the given config, plus the daemon's management
   * password file (mode 0600) referenced from the config. Removes the previous config file of the
   * same daemon index first.
   *
   * @param networkMode "nat" or "routed"; surfaced in the rendered config for the firewall script.
   */
  public synchronized void writeDaemon(
      ServerConfig config,
      ServerConfigGenerator generator,
      OpnlProperties props,
      String networkMode) {
    try {
      Files.createDirectories(configDir);
      Files.createDirectories(logDir);
      // Client-config-dir must exist for `ccd-exclusive` to work.
      Files.createDirectories(Path.of(props.openvpn().ccdDir()));
    } catch (IOException e) {
      throw ApiException.internal("config_write", "Cannot create config dirs: " + e.getMessage());
    }

    DaemonRender render = renderDaemon(config, generator, props, networkMode);
    writeMgmtPassword(config.daemonIndex(), render.mgmtPassword());

    Path file = configDir.resolve("daemon-" + config.daemonIndex() + ".conf");
    try {
      Files.writeString(file, render.conf(), StandardCharsets.UTF_8);
      log.info("Wrote {}", file);
    } catch (IOException e) {
      throw ApiException.internal("config_write", "Cannot write " + file + ": " + e.getMessage());
    }
  }

  /**
   * Renders a daemon config and its management password for distribution without writing to disk.
   * The embedded paths use this instance's configured volume paths, so the rendered config is valid
   * in any container that mounts those paths identically (the node-agent compose contract).
   */
  public DaemonRender renderDaemon(
      ServerConfig config,
      ServerConfigGenerator generator,
      OpnlProperties props,
      String networkMode) {
    String mgmtPassword = props.openvpn().mgmtPassword();
    if (mgmtPassword == null || mgmtPassword.isBlank()) {
      throw ApiException.internal(
          "mgmt_password_missing",
          "OPNL_OPENVPN_MGMT_PASSWORD is required before daemon configs can be written");
    }
    Path mgmtPassFile = configDir.resolve("daemon-" + config.daemonIndex() + ".mgmt-pass");
    String rendered =
        generator.render(
            config,
            props.openvpn().pkiDir(),
            props.openvpn().ccdDir(),
            props.openvpn().scriptsDir(),
            logDir.toString(),
            networkMode,
            mgmtPassFile.toString());
    return new DaemonRender(config.daemonIndex(), rendered, mgmtPassword);
  }

  /** A rendered daemon config plus the management password the daemon's config points at. */
  public record DaemonRender(int daemonIndex, String conf, String mgmtPassword) {}

  /** Removes a daemon config file (used when disabling a daemon). */
  public synchronized void removeDaemon(int daemonIndex) {
    Path file = configDir.resolve("daemon-" + daemonIndex + ".conf");
    try {
      Files.deleteIfExists(file);
      Files.deleteIfExists(configDir.resolve("daemon-" + daemonIndex + ".mgmt-pass"));
      log.info("Removed {}", file);
    } catch (IOException e) {
      throw ApiException.internal("config_write", "Cannot remove " + file + ": " + e.getMessage());
    }
  }

  /** Persists the daemon's management password with owner-only permissions. */
  private Path writeMgmtPassword(int daemonIndex, String password) {
    Path file = configDir.resolve("daemon-" + daemonIndex + ".mgmt-pass");
    try {
      Files.writeString(file, password, StandardCharsets.UTF_8);
      try {
        Files.setPosixFilePermissions(
            file,
            java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
      } catch (UnsupportedOperationException ignored) {
        // Non-POSIX filesystem: rely on the platform defaults.
      }
      return file;
    } catch (IOException e) {
      throw ApiException.internal("config_write", "Cannot write " + file + ": " + e.getMessage());
    }
  }
}
