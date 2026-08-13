package com.opnl.vpn.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.api.admin.BackupInfo;
import com.opnl.vpn.api.admin.RestoreResult;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Creates, lists, downloads and restores backup archives. Each archive is a ZIP containing a
 * consistent SQLite snapshot ({@code opnl.db} via {@code VACUUM INTO}), the PKI, config and CCD
 * trees and a {@code manifest.json}. Archives live under {@code <dataDir>/backups}. Restoring
 * replaces the file assets immediately; a swapped database requires a backend restart.
 */
@Slf4j
@Service
public class BackupService {

  private static final String MANIFEST_FILE = "manifest.json";
  private static final String DB_ENTRY = "opnl.db";
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final Pattern BACKUP_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.zip$");

  private final DataSource dataSource;
  private final Environment environment;
  private final OpnlProperties properties;
  private final AuditLogService auditLogService;
  private final ObjectMapper objectMapper;

  public BackupService(
      DataSource dataSource,
      Environment environment,
      OpnlProperties properties,
      AuditLogService auditLogService,
      ObjectMapper objectMapper) {
    this.dataSource = dataSource;
    this.environment = environment;
    this.properties = properties;
    this.auditLogService = auditLogService;
    this.objectMapper = objectMapper;
  }

  /** Creates a backup archive and returns its metadata. */
  public synchronized BackupInfo createBackup() {
    Path dir = backupsDir();
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw ApiException.internal("backup_io", "Cannot create backup directory: " + e.getMessage());
    }
    String name = "opnl-backup-" + STAMP.format(Instant.now()) + ".zip";
    Path target = dir.resolve(name);
    Path staging = newStaging(dir, "backup-");
    try {
      Files.createDirectories(staging);
      boolean sqlite = isSqlite();
      if (sqlite) {
        vacuumInto(staging.resolve(DB_ENTRY));
      }
      copyTree(pkiDir(), staging.resolve("pki"));
      copyTree(configDir(), staging.resolve("config"));
      copyTree(ccdDir(), staging.resolve("ccd"));
      writeManifest(staging, sqlite);
      zipTree(staging, target);
      log.info("Backup created: {} ({} bytes)", name, Files.size(target));
      auditLogService.record(
          "BACKUP_CREATE",
          AuditLogService.CAT_BACKUP,
          name,
          "backup",
          Map.of("size", Files.size(target)));
      return infoFor(target);
    } catch (ApiException e) {
      deleteRecursively(staging);
      throw e;
    } catch (Exception e) {
      deleteRecursively(staging);
      log.warn("Backup failed: {}", e.getMessage());
      throw ApiException.internal("backup_failed", "Backup failed: " + e.getMessage());
    }
  }

  public List<BackupInfo> listBackups() {
    List<BackupInfo> backups = new ArrayList<>();
    Path dir = backupsDir();
    if (!Files.isDirectory(dir)) {
      return backups;
    }
    try (var stream = Files.list(dir)) {
      stream
          .filter(
              p ->
                  p.getFileName() != null
                      && BACKUP_NAME.matcher(p.getFileName().toString()).matches())
          .sorted(Comparator.comparing(Path::getFileName).reversed())
          .forEach(p -> backups.add(infoFor(p)));
    } catch (IOException e) {
      throw ApiException.internal("backup_io", "Cannot list backups: " + e.getMessage());
    }
    return backups;
  }

  /** Resolves a backup file by name, rejecting path traversal and unknown files. */
  public Path resolveBackup(String name) {
    if (name == null || !BACKUP_NAME.matcher(name).matches() || name.contains("..")) {
      throw ApiException.notFound("backup_not_found", "Backup not found");
    }
    Path file = backupsDir().resolve(name).normalize();
    if (!file.startsWith(backupsDir().normalize()) || !Files.isRegularFile(file)) {
      throw ApiException.notFound("backup_not_found", "Backup not found");
    }
    return file;
  }

  public RestoreResult restore(String name) {
    Path archive = resolveBackup(name);
    Path staging = newStaging(backupsDir(), "restore-");
    try {
      Files.createDirectories(staging);
      unzip(archive, staging);
      if (!Files.exists(staging.resolve(DB_ENTRY))
          && !Files.exists(staging.resolve(MANIFEST_FILE))) {
        throw ApiException.badRequest("invalid_backup", "Archive is not a valid opnl backup");
      }
      replaceTree(staging.resolve("pki"), pkiDir());
      replaceTree(staging.resolve("config"), configDir());
      replaceTree(staging.resolve("ccd"), ccdDir());
      boolean replacedDb = false;
      if (isSqlite() && Files.exists(staging.resolve(DB_ENTRY))) {
        Files.copy(
            staging.resolve(DB_ENTRY),
            currentDbPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES);
        replacedDb = true;
      }
      String message =
          replacedDb
              ? "Restored from "
                  + name
                  + "; restart the backend for the database swap to take effect"
              : "Restored file assets from " + name;
      log.info("Restored backup {} (db replaced: {})", name, replacedDb);
      auditLogService.record(
          "BACKUP_RESTORE",
          AuditLogService.CAT_BACKUP,
          name,
          "backup",
          Map.of("dbReplaced", replacedDb));
      return new RestoreResult(replacedDb, message);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      log.warn("Restore failed: {}", e.getMessage());
      throw ApiException.internal("restore_failed", "Restore failed: " + e.getMessage());
    } finally {
      deleteRecursively(staging);
    }
  }

  // ---- internals ----------------------------------------------------------

  private Path backupsDir() {
    return Path.of(properties.dataDir()).resolve("backups");
  }

  private Path pkiDir() {
    return Path.of(properties.openvpn().pkiDir());
  }

  private Path configDir() {
    return Path.of(properties.openvpn().configDir());
  }

  private Path ccdDir() {
    return Path.of(properties.openvpn().ccdDir());
  }

  private boolean isSqlite() {
    return environment.getProperty("spring.datasource.url", "").startsWith("jdbc:sqlite:");
  }

  /** Produces a consistent copy of the live SQLite database via VACUUM INTO. */
  private void vacuumInto(Path target) {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      String escaped = target.toAbsolutePath().toString().replace("'", "''");
      statement.execute("VACUUM INTO '" + escaped + "'");
    } catch (Exception e) {
      throw ApiException.internal(
          "db_snapshot_failed", "Database snapshot failed: " + e.getMessage());
    }
  }

  private Path currentDbPath() {
    String url = environment.getProperty("spring.datasource.url", "");
    if (!url.startsWith("jdbc:sqlite:")) {
      throw ApiException.internal(
          "unsupported_db", "Database restore requires a SQLite datasource");
    }
    String path = url.substring("jdbc:sqlite:".length());
    int q = path.indexOf('?');
    if (q >= 0) {
      path = path.substring(0, q);
    }
    return Path.of(path);
  }

  private void writeManifest(Path staging, boolean sqlite) throws IOException {
    Map<String, Object> manifest =
        Map.of(
            "schemaVersion",
            1,
            "createdAt",
            Instant.now().toString(),
            "brand",
            properties.brandName(),
            "db",
            sqlite ? "sqlite-snapshot" : "skipped");
    objectMapper.writeValue(staging.resolve(MANIFEST_FILE).toFile(), manifest);
  }

  private BackupInfo infoFor(Path file) {
    try {
      Instant createdAt = Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis());
      return new BackupInfo(file.getFileName().toString(), Files.size(file), createdAt);
    } catch (IOException e) {
      throw ApiException.internal("backup_io", "Cannot stat backup file");
    }
  }

  private static Path newStaging(Path dir, String prefix) {
    try {
      return Files.createTempDirectory(dir, prefix);
    } catch (IOException e) {
      throw ApiException.internal("backup_io", "Cannot create staging directory");
    }
  }

  /** Recursively copies a directory tree into an archive stream, rooted at {@code root}. */
  private void zipTree(Path root, Path target) throws IOException {
    try (OutputStream fileOut = Files.newOutputStream(target);
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOut))) {
      Path normalizedRoot = root.toAbsolutePath().normalize();
      try (var stream = Files.walk(normalizedRoot)) {
        for (Path path : stream.filter(Files::isRegularFile).toList()) {
          String relative = normalizedRoot.relativize(path).toString().replace('\\', '/');
          zip.putNextEntry(new ZipEntry(relative));
          Files.copy(path, zip);
          zip.closeEntry();
        }
      }
    }
  }

  /** Extracts an archive, guarding against Zip Slip path traversal. */
  private void unzip(Path archive, Path target) throws IOException {
    Path normalizedRoot = target.toAbsolutePath().normalize();
    try (InputStream fileIn = Files.newInputStream(archive);
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(fileIn))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        Path resolved = normalizedRoot.resolve(entry.getName()).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
          throw ApiException.badRequest("invalid_backup", "Archive contains an unsafe path");
        }
        Files.createDirectories(resolved.getParent());
        Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
        zip.closeEntry();
      }
    }
  }

  /** Replaces the destination directory contents with a copy of the source tree. */
  private void replaceTree(Path source, Path destination) throws IOException {
    Files.createDirectories(destination);
    deleteRecursively(destination);
    if (Files.isDirectory(source)) {
      copyTree(source, destination);
    }
  }

  private void copyTree(Path source, Path destination) throws IOException {
    if (!Files.isDirectory(source)) {
      return;
    }
    try (var stream = Files.walk(source)) {
      for (Path path : stream.sorted().toList()) {
        Path relative = source.relativize(path);
        Path target = destination.resolve(relative.toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else if (Files.isRegularFile(path)) {
          Files.createDirectories(target.getParent());
          Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static void deleteRecursively(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      log.warn("Cannot clean up staging directory {}: {}", dir, e.getMessage());
    }
  }
}
