package com.opnl.vpn.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.node.NodeConfigBundleService.FileEntry;
import com.opnl.vpn.node.NodeConfigBundleService.NodeConfigBundle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Pulls this gateway's config bundle from the central backend ({@code /internal/node/config}) and
 * applies it to the local gateway volumes: daemon configs + management passwords, the PKI files the
 * daemons reference (including the CRL, so revocations reach this node), the client-config-dir,
 * helper scripts and dnsmasq pinning configs.
 *
 * <p>Files are written atomically (temp file + rename) with conservative permissions, and stale
 * managed files that left the bundle are removed, so the node always mirrors the central view. The
 * node's openvpn container watcher reloads daemons when configs change; identical content (same
 * bundle version) is skipped and never triggers a reload. Active only under the {@code agent}
 * Spring profile.
 */
@Slf4j
@Service
@Profile("agent")
public class AgentConfigSyncService {

  private static final Pattern DAEMON_FILE = Pattern.compile("daemon-\\d+\\.(conf|mgmt-pass)");
  private static final Pattern PKI_FILE = Pattern.compile("(ca|server|ta)\\.(crt|key)|crl\\.pem");
  private static final Pattern SCRIPT_FILE = Pattern.compile(".*\\.(sh|py)");
  private static final Pattern DNSMASQ_FILE = Pattern.compile(".*\\.conf");

  private final AgentRegistrationService registration;
  private final OpnlProperties opnlProperties;
  private final ObjectMapper objectMapper;

  private volatile String lastVersion;

  public AgentConfigSyncService(
      AgentRegistrationService registration,
      OpnlProperties opnlProperties,
      ObjectMapper objectMapper) {
    this.registration = registration;
    this.opnlProperties = opnlProperties;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelayString = "${opnl.agent.sync-seconds:60}s", initialDelay = 15_000)
  public void tick() {
    String nodeId = registration.currentNodeId();
    if (nodeId == null) {
      return;
    }
    try {
      String body = objectMapper.writeValueAsString(Map.of("nodeId", nodeId));
      String response = registration.postJson("/internal/node/config", body);
      if (response == null) {
        return;
      }
      NodeConfigBundle bundle = objectMapper.readValue(response, NodeConfigBundle.class);
      if (bundle.version() == null || bundle.version().isBlank()) {
        log.warn("Config bundle missing version; ignoring");
        return;
      }
      apply(bundle);
    } catch (Exception e) {
      log.warn("Config sync failed: {}", e.getMessage());
    }
  }

  private void apply(NodeConfigBundle bundle) {
    if (bundle.version().equals(lastVersion)) {
      return;
    }
    writeFiles(configDir(), bundle.daemons(), DAEMON_FILE, false);
    writeFiles(pkiDir(), bundle.pki(), PKI_FILE, false);
    writeFiles(ccdDir(), bundle.ccd(), null, false);
    writeFiles(scriptsDir(), bundle.scripts(), SCRIPT_FILE, true);
    writeFiles(configDir().resolve("dnsmasq.d"), bundle.dnsmasq(), DNSMASQ_FILE, false);
    lastVersion = bundle.version();
    log.info("Applied node config bundle {} ({} files)", bundle.version(), totalFiles(bundle));
  }

  /**
   * Writes every bundle entry into {@code dir} atomically, removes managed files not present in the
   * bundle, and applies restrictive permissions. Sensitive files (management passwords, private
   * keys) are chmod 0600; helper scripts are owner-executable.
   */
  private void writeFiles(Path dir, List<FileEntry> entries, Pattern managed, boolean scripts) {
    if (entries.isEmpty() && managed == null) {
      return;
    }
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      log.warn("Cannot create dir {}: {}", dir, e.getMessage());
      return;
    }
    Set<String> applied = new HashSet<>();
    for (FileEntry entry : entries) {
      Path target = resolveSafely(dir, entry.name());
      writeAtomic(target, entry.content());
      applyPermissions(target, scripts);
      applied.add(entry.name());
    }
    Pattern managedPattern = managed == null ? Pattern.compile(".*") : managed;
    try (Stream<Path> stream = Files.list(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> managedPattern.matcher(p.getFileName().toString()).matches())
          .filter(p -> !applied.contains(p.getFileName().toString()))
          .forEach(
              stale -> {
                try {
                  Files.deleteIfExists(stale);
                  log.debug("Removed stale {} on node", stale);
                } catch (IOException e) {
                  log.warn("Cannot remove stale {}: {}", stale, e.getMessage());
                }
              });
    } catch (IOException e) {
      log.warn("Cannot reconcile {}: {}", dir, e.getMessage());
    }
  }

  /** Guards against path traversal: the resolved path must stay inside the target directory. */
  private Path resolveSafely(Path dir, String name) {
    Path target = dir.resolve(name).normalize();
    if (!target.startsWith(dir.normalize())) {
      throw new IllegalArgumentException("Unsafe bundle file name: " + name);
    }
    return target;
  }

  private void writeAtomic(Path target, String content) {
    try {
      Files.createDirectories(target.getParent());
      Path temp = target.resolveSibling(target.getFileName() + ".tmp");
      Files.writeString(temp, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot write " + target + ": " + e.getMessage());
    }
  }

  private void applyPermissions(Path target, boolean executable) {
    String name = target.getFileName().toString();
    boolean sensitive = name.endsWith(".mgmt-pass") || name.equals("server.key");
    if (executable) {
      if (!target.toFile().setExecutable(true, true)) {
        log.warn("Cannot mark {} executable", target);
      }
      return;
    }
    if (!sensitive) {
      return;
    }
    try {
      Files.setPosixFilePermissions(
          target,
          java.util.EnumSet.of(
              java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Non-POSIX filesystem: rely on the platform defaults.
    }
  }

  private int totalFiles(NodeConfigBundle bundle) {
    return bundle.daemons().size()
        + bundle.pki().size()
        + bundle.ccd().size()
        + bundle.scripts().size()
        + bundle.dnsmasq().size();
  }

  private Path configDir() {
    return Path.of(opnlProperties.openvpn().configDir());
  }

  private Path pkiDir() {
    return Path.of(opnlProperties.openvpn().pkiDir());
  }

  private Path ccdDir() {
    return Path.of(opnlProperties.openvpn().ccdDir());
  }

  private Path scriptsDir() {
    return Path.of(opnlProperties.openvpn().scriptsDir());
  }
}
