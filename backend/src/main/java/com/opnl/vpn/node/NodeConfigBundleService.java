package com.opnl.vpn.node;

import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.network.ConfigWriter;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.network.ServerConfigGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the configuration bundle a remote node agent pulls over {@code /internal/node/config}
 * to run its gateways. The bundle contains only the daemons bound to the calling node:
 *
 * <ul>
 *   <li>{@code daemons} — rendered {@code daemon-N.conf} files plus the management password file
 *       each config references (paths use the standard container volume layout);
 *   <li>{@code pki} — {@code ca.crt}, {@code server.crt}/{@code server.key}, {@code ta.key} and the
 *       {@code crl.pem} the daemons load, so a revoked certificate stops being accepted on every
 *       node as soon as the agent pulls a new bundle;
 *   <li>{@code ccd} — the client-config-dir entries (static IPs / per-user pushes);
 *   <li>{@code scripts} — the rendered helper scripts (already token-substituted);
 *   <li>{@code dnsmasq} — the domain-pinning and DNS-override configs.
 * </ul>
 *
 * The {@code version} is a SHA-256 over all entries; the agent skips rewriting when it matches the
 * version it last applied. Content is served only on the internal mTLS connector to an agent whose
 * client certificate CN matches the requested node, and only from the node's pinned admin IP when
 * one is set.
 */
@Slf4j
@Service
public class NodeConfigBundleService {

  private static final List<String> PKI_FILES =
      List.of("ca.crt", "server.crt", "server.key", "ta.key", "crl.pem");

  private final DaemonService daemonService;
  private final ConfigWriter configWriter;
  private final ServerConfigGenerator generator;
  private final OpnlProperties properties;

  public NodeConfigBundleService(
      DaemonService daemonService,
      ConfigWriter configWriter,
      ServerConfigGenerator generator,
      OpnlProperties properties) {
    this.daemonService = daemonService;
    this.configWriter = configWriter;
    this.generator = generator;
    this.properties = properties;
  }

  @Transactional(readOnly = true)
  public NodeConfigBundle bundleForNode(String nodeId) {
    List<FileEntry> daemons = new ArrayList<>();
    for (var daemon : daemonService.enabledDaemonsForNode(nodeId)) {
      ConfigWriter.DaemonRender render =
          configWriter.renderDaemon(
              daemonService.toServerConfig(daemon),
              generator,
              properties,
              daemonService.networkMode());
      daemons.add(new FileEntry("daemon-" + render.daemonIndex() + ".conf", render.conf()));
      daemons.add(
          new FileEntry("daemon-" + render.daemonIndex() + ".mgmt-pass", render.mgmtPassword()));
    }
    List<FileEntry> pki = readDirectory(pkiDir(), PKI_FILES::contains);
    List<FileEntry> ccd = readDirectory(ccdDir(), name -> !name.startsWith("."));
    List<FileEntry> scripts =
        readDirectory(scriptsDir(), name -> name.endsWith(".sh") || name.endsWith(".py"));
    List<FileEntry> dnsmasq =
        readDirectory(configDir().resolve("dnsmasq.d"), name -> name.endsWith(".conf"));
    String version = hash(List.of(daemons, pki, ccd, scripts, dnsmasq));
    log.info(
        "Assembled config bundle for node {} (version {}, {} daemon files)",
        nodeId,
        version.substring(0, Math.min(12, version.length())),
        daemons.size() / 2);
    return new NodeConfigBundle(version, daemons, pki, ccd, scripts, dnsmasq);
  }

  /**
   * Reads regular files from a directory, each as name + content; missing dirs yield no entries.
   */
  private List<FileEntry> readDirectory(Path dir, java.util.function.Predicate<String> accept) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<FileEntry> entries = new ArrayList<>();
    try (Stream<Path> stream = Files.list(dir)) {
      stream
          .filter(Files::isRegularFile)
          .map(Path::getFileName)
          .map(Path::toString)
          .filter(accept)
          .sorted()
          .forEach(
              name -> {
                try {
                  entries.add(new FileEntry(name, Files.readString(dir.resolve(name))));
                } catch (IOException e) {
                  log.warn("Cannot read {}: {}", dir.resolve(name), e.getMessage());
                }
              });
    } catch (IOException e) {
      log.warn("Cannot list {}: {}", dir, e.getMessage());
    }
    return entries;
  }

  /** SHA-256 over every entry (categories and names in fixed order) for change detection. */
  private String hash(List<List<FileEntry>> categories) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (List<FileEntry> category : categories) {
        for (FileEntry entry : category) {
          digest.update(entry.name().getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
          digest.update(entry.content().getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
        }
      }
      byte[] bytes = digest.digest();
      StringBuilder sb = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private Path pkiDir() {
    return Path.of(properties.openvpn().pkiDir());
  }

  private Path ccdDir() {
    return Path.of(properties.openvpn().ccdDir());
  }

  private Path scriptsDir() {
    return Path.of(properties.openvpn().scriptsDir());
  }

  private Path configDir() {
    return Path.of(properties.openvpn().configDir());
  }

  /** A file within the bundle, addressed by a safe relative name. */
  public record FileEntry(String name, String content) {}

  /** The full config bundle a node agent applies to its gateway. */
  public record NodeConfigBundle(
      String version,
      List<FileEntry> daemons,
      List<FileEntry> pki,
      List<FileEntry> ccd,
      List<FileEntry> scripts,
      List<FileEntry> dnsmasq) {}
}
