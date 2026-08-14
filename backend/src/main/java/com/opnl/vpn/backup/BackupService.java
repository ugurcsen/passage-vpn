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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    } catch (Exception e) {
      log.warn("Backup failed: {}", e.getMessage());
      throw ApiException.internal("backup_failed", "Backup failed: " + e.getMessage());
    } finally {
      // The staging tree holds PKI, config and a database snapshot; never leave it behind.
      deleteRecursively(staging);
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

  /**
   * Stores an uploaded backup archive into the backups directory. The archive is validated before
   * it is kept: it must be a ZIP containing a {@code manifest.json} or {@code opnl.db} marker and
   * must not contain unsafe paths. A name matching the backup filename pattern is kept; anything
   * else is renamed to an {@code imported-<stamp>.zip} form. Collisions get a timestamp suffix.
   */
  public synchronized BackupInfo importArchive(InputStream input, String originalName) {
    Path dir = backupsDir();
    try {
      Files.createDirectories(dir);
      Path temp = Files.createTempFile(dir, "import-", ".zip");
      try {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        validateArchiveContent(temp);
        String name = resolveImportName(originalName);
        Path target = dir.resolve(name);
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.info("Backup imported: {} ({} bytes)", name, Files.size(target));
        auditLogService.record(
            "BACKUP_IMPORT",
            AuditLogService.CAT_BACKUP,
            name,
            "backup",
            Map.of("size", Files.size(target)));
        return infoFor(target);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      log.warn("Backup import failed: {}", e.getMessage());
      throw ApiException.internal("import_failed", "Backup import failed: " + e.getMessage());
    }
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
      Path rollback = null;
      if (isSqlite() && Files.exists(staging.resolve(DB_ENTRY))) {
        Path snapshot = staging.resolve(DB_ENTRY);
        validateSnapshot(snapshot);
        rollback = createRollbackCopy();
        replaceDatabase(snapshot);
        replacedDb = true;
      }
      String message =
          replacedDb
              ? "Restored from "
                  + name
                  + "; restart the backend for the database swap to take effect. "
                  + "Previous database preserved at "
                  + rollback
              : "Restored file assets from " + name;
      log.info("Restored backup {} (db replaced: {})", name, replacedDb);
      auditLogService.record(
          "BACKUP_RESTORE",
          AuditLogService.CAT_BACKUP,
          name,
          "backup",
          Map.of("dbReplaced", replacedDb, "rollback", String.valueOf(rollback)));
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

  /**
   * Replaces the live SQLite database file with a restored snapshot. SQLite WAL mode keeps {@code
   * -wal}/{@code -shm} sidecar files next to the database; copying the main file over while stale
   * sidecars remain corrupts the database on the next open, so the sidecars are removed first and
   * the snapshot is swapped in atomically. A backend restart is still required because the running
   * pool keeps the previous file open.
   */
  private void replaceDatabase(Path snapshot) throws IOException {
    Path db = currentDbPath();
    Path temp = Files.createTempFile(db.getParent(), "restore-", ".db");
    try {
      Files.copy(snapshot, temp, StandardCopyOption.REPLACE_EXISTING);
      for (String suffix : List.of("-wal", "-shm")) {
        Files.deleteIfExists(Path.of(db.toString() + suffix));
      }
      try {
        Files.move(temp, db, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, db, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /**
   * Runs the safety checks that must pass before a restored database snapshot is swapped in: an
   * integrity check, a foreign-key check and a schema-version comparison against the live database.
   * A mismatch in any of these refuses the restore so the backend never boots against a database it
   * cannot read (which would otherwise crash-loop under Docker's restart policy).
   */
  private void validateSnapshot(Path snapshot) throws IOException {
    String url = "jdbc:sqlite:" + snapshot.toAbsolutePath();
    try (Connection connection = java.sql.DriverManager.getConnection(url)) {
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
        rs.next();
        if (!"ok".equals(rs.getString(1))) {
          throw ApiException.badRequest(
              "corrupt_backup", "Backup database failed the integrity check");
        }
      }
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("PRAGMA foreign_key_check")) {
        if (rs.next()) {
          throw ApiException.badRequest(
              "corrupt_backup", "Backup database has foreign key violations");
        }
      }
      String snapshotVersion = maxSchemaVersion(connection);
      try (Connection live = dataSource.getConnection()) {
        String liveVersion = maxSchemaVersion(live);
        if (snapshotVersion == null && liveVersion != null) {
          throw ApiException.badRequest(
              "schema_mismatch", "Backup predates the current schema and cannot be restored");
        }
        if (snapshotVersion != null && liveVersion == null) {
          throw ApiException.badRequest(
              "schema_mismatch", "Backup carries a schema the current version cannot restore");
        }
        if (snapshotVersion != null && !snapshotVersion.equals(liveVersion)) {
          throw ApiException.badRequest(
              "schema_mismatch",
              "Backup schema version "
                  + snapshotVersion
                  + " does not match the current version "
                  + liveVersion);
        }
      }
    } catch (SQLException e) {
      throw ApiException.internal(
          "db_check_failed", "Backup database check failed: " + e.getMessage());
    }
  }

  /** Highest applied Flyway migration of a database, or {@code null} when unmanaged. */
  private static String maxSchemaVersion(Connection connection) {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      return null;
    }
  }

  /**
   * Keeps a consistent snapshot of the pre-restore database so a restore that breaks the next boot
   * is reversible. Only the newest rollback copies are kept to avoid unbounded disk usage.
   */
  private Path createRollbackCopy() throws IOException {
    Path dir = Path.of(properties.dataDir()).resolve("rollback");
    Files.createDirectories(dir);
    String stamp = STAMP.format(Instant.now());
    Path target = dir.resolve("opnl.db.pre-restore-" + stamp);
    vacuumInto(target);
    pruneRollbackCopies(dir);
    log.info("Pre-restore database preserved at {}", target);
    return target;
  }

  private static void pruneRollbackCopies(Path dir) throws IOException {
    List<Path> copies;
    try (var stream = Files.list(dir)) {
      copies =
          stream
              .filter(p -> p.getFileName() != null)
              .filter(p -> p.getFileName().toString().startsWith("opnl.db.pre-restore-"))
              .sorted(Comparator.comparing(Path::getFileName).reversed())
              .toList();
    }
    for (Path copy : copies.subList(Math.min(copies.size(), 5), copies.size())) {
      Files.deleteIfExists(copy);
    }
  }

  /** Scans an uploaded archive for the opnl markers and unsafe paths before it is kept. */
  private static void validateArchiveContent(Path archive) throws IOException {
    Path normalizedRoot = archive.toAbsolutePath().normalize();
    boolean marker = false;
    try (InputStream fileIn = Files.newInputStream(archive);
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(fileIn))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        if (name.equals(MANIFEST_FILE) || name.equals(DB_ENTRY)) {
          marker = true;
        }
        Path resolved = normalizedRoot.resolve(name).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
          throw ApiException.badRequest("invalid_backup", "Archive contains an unsafe path");
        }
        zip.closeEntry();
      }
    }
    if (!marker) {
      throw ApiException.badRequest(
          "invalid_backup", "Archive is not a valid opnl backup (no manifest or database)");
    }
  }

  /** Resolves a collision-free storage name for an uploaded archive. */
  private String resolveImportName(String originalName) {
    String candidate =
        originalName != null && BACKUP_NAME.matcher(originalName).matches()
            ? originalName
            : "imported-" + STAMP.format(Instant.now()) + ".zip";
    Path target = backupsDir().resolve(candidate);
    while (Files.exists(target)) {
      String base = candidate.substring(0, candidate.length() - ".zip".length());
      candidate = base + "-" + STAMP.format(Instant.now().plusSeconds(1)) + ".zip";
      target = backupsDir().resolve(candidate);
    }
    return candidate;
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
        markScriptExecutable(resolved);
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
          markScriptExecutable(target);
        }
      }
    }
  }

  /**
   * Restores the owner-execute bit on OpenVPN helper scripts. ZIP entries and plain copies do not
   * carry the original permissions, so extracted {@code .sh}/{@code .py} files would otherwise come
   * back non-executable and break daemon reloads with {@code Options error: ... Permission denied}.
   */
  private static void markScriptExecutable(Path file) {
    if (file.getFileName() == null) {
      return;
    }
    String name = file.getFileName().toString();
    if (name.endsWith(".sh") || name.endsWith(".py")) {
      file.toFile().setExecutable(true, true);
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
