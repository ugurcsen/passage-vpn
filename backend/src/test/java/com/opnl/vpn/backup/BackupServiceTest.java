package com.opnl.vpn.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opnl.vpn.api.admin.BackupInfo;
import com.opnl.vpn.api.admin.RestoreResult;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Integration tests for the backup service against a real temporary SQLite database. */
class BackupServiceTest {

  private Path tempDir;
  private Path dbPath;
  private HikariDataSource dataSource;
  private BackupService backupService;
  private AuditLogService auditLogService;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("opnl-backup-test");
    dbPath = tempDir.resolve("opnl.db");
    createDatabase(dbPath);

    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl("jdbc:sqlite:" + dbPath);
    dataSource.setMaximumPoolSize(2);

    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.datasource.url", "jdbc:sqlite:" + dbPath);
    environment.setProperty("opnl.data-dir", tempDir.toString());

    OpnlProperties properties =
        new OpnlProperties(
            tempDir.toString(),
            "OpenVPN Panel",
            "secret",
            null,
            null,
            new OpnlProperties.OpenVpn(
                "openvpn",
                7505,
                "localhost",
                tempDir.resolve("pki").toString(),
                tempDir.resolve("ccd").toString(),
                tempDir.resolve("config").toString(),
                tempDir.resolve("config").resolve("scripts").toString(),
                "./openvpn/scripts",
                "http://backend:8080",
                "easyrsa",
                tempDir.resolve("logs").toString(),
                "mgmt-pass",
                730,
                1194,
                1194,
                1195,
                1195));

    Files.createDirectories(tempDir.resolve("pki"));
    Files.createDirectories(tempDir.resolve("ccd"));
    Files.createDirectories(tempDir.resolve("config"));
    Files.writeString(tempDir.resolve("pki/ca.crt"), "TEST CA");
    Files.writeString(tempDir.resolve("ccd/user1"), "ifconfig-push 10.8.0.2 255.255.255.0");
    Files.writeString(tempDir.resolve("config/daemon-0.conf"), "port 1194");

    auditLogService = mock(AuditLogService.class);
    backupService =
        new BackupService(dataSource, environment, properties, auditLogService, new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    dataSource.close();
  }

  @Test
  void createBackupStoresArchiveWithDbPkiConfigCcdAndManifest() throws Exception {
    BackupInfo info = backupService.createBackup();

    assertThat(info.name()).startsWith("opnl-backup-").endsWith(".zip");
    assertThat(info.sizeBytes()).isGreaterThan(0);
    List<BackupInfo> backups = backupService.listBackups();
    assertThat(backups).hasSize(1);
    assertThat(backups.get(0).name()).isEqualTo(info.name());

    // The staging tree (PKI/config/db copies) must not be left behind.
    try (var stream = Files.list(tempDir.resolve("backups"))) {
      assertThat(stream.filter(Files::isDirectory)).isEmpty();
    }

    Path archive = tempDir.resolve("backups").resolve(info.name());
    assertThat(archive).exists();

    Path extracted = Files.createTempDirectory("opnl-backup-extract");
    try {
      extract(archive, extracted);
      assertThat(extracted.resolve("manifest.json")).exists();
      assertThat(extracted.resolve("opnl.db")).exists();
      assertThat(extracted.resolve("pki/ca.crt")).exists();
      assertThat(extracted.resolve("ccd/user1")).exists();
      assertThat(extracted.resolve("config/daemon-0.conf")).exists();
      assertThat(Files.readString(extracted.resolve("pki/ca.crt"))).isEqualTo("TEST CA");

      // The DB snapshot must contain the live row.
      try (Connection conn = dataSource.getConnection();
          Statement st = conn.createStatement()) {
        st.execute("ATTACH DATABASE '" + extracted.resolve("opnl.db") + "' AS snap");
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM widgets WHERE name = 'widget-1'");
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    } finally {
      deleteRecursively(extracted);
    }

    verify(auditLogService)
        .record(eq("BACKUP_CREATE"), eq(AuditLogService.CAT_BACKUP), any(), any(), any());
  }

  @Test
  void restoreRevertsFileAssetsAndDatabaseSnapshot() throws Exception {
    BackupInfo info = backupService.createBackup();

    Files.writeString(tempDir.resolve("config/daemon-0.conf"), "port 9999");
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      st.executeUpdate("INSERT INTO widgets (name) VALUES ('widget-2')");
    }

    RestoreResult result = backupService.restore(info.name());

    assertThat(result.restartRequired()).isTrue();
    assertThat(Files.readString(tempDir.resolve("config/daemon-0.conf"))).isEqualTo("port 1194");
    // The swapped database file must contain the snapshot; a fresh connection reads it (the
    // existing pool keeps stale state until the backend restarts, hence restartRequired).
    try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM widgets")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
    verify(auditLogService)
        .record(eq("BACKUP_RESTORE"), eq(AuditLogService.CAT_BACKUP), any(), any(), any());
  }

  @Test
  void restoreKeepsHelperScriptsExecutable() throws Exception {
    Path script = tempDir.resolve("config/scripts/client-connect.sh");
    Files.createDirectories(script.getParent());
    Files.writeString(script, "#!/bin/sh\necho hi\n");
    script.toFile().setExecutable(true, true);
    assertThat(script.toFile().canExecute()).isTrue();

    BackupInfo info = backupService.createBackup();

    // A previous restore wiped the executable bit; the next restore must bring it back.
    script.toFile().setExecutable(false, true);
    assertThat(script.toFile().canExecute()).isFalse();

    backupService.restore(info.name());

    assertThat(script).exists();
    assertThat(script.toFile().canExecute()).isTrue();
  }

  @Test
  void resolveBackupRejectsTraversal() {
    assertThatThrownBy(() -> backupService.resolveBackup("../secret.zip"))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getStatus())
        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
  }

  @Test
  void importArchiveAcceptsValidArchiveAndKeepsName() throws Exception {
    BackupInfo original = backupService.createBackup();
    byte[] bytes = Files.readAllBytes(tempDir.resolve("backups").resolve(original.name()));

    BackupInfo imported =
        backupService.importArchive(new java.io.ByteArrayInputStream(bytes), "restored-copy.zip");

    assertThat(imported.name()).isEqualTo("restored-copy.zip");
    assertThat(imported.sizeBytes()).isEqualTo(original.sizeBytes());
    assertThat(tempDir.resolve("backups").resolve(imported.name())).exists();
    List<BackupInfo> backups = backupService.listBackups();
    assertThat(backups).hasSize(2);
    verify(auditLogService)
        .record(eq("BACKUP_IMPORT"), eq(AuditLogService.CAT_BACKUP), any(), any(), any());
  }

  @Test
  void importArchiveResolvesCollisionWithSuffix() throws Exception {
    BackupInfo original = backupService.createBackup();
    byte[] bytes = Files.readAllBytes(tempDir.resolve("backups").resolve(original.name()));

    BackupInfo imported =
        backupService.importArchive(new java.io.ByteArrayInputStream(bytes), original.name());

    assertThat(imported.name()).startsWith("opnl-backup-").endsWith(".zip");
    assertThat(imported.name()).isNotEqualTo(original.name());
    assertThat(tempDir.resolve("backups").resolve(imported.name())).exists();
  }

  @Test
  void importArchiveRenamesNonConformingName() throws Exception {
    BackupInfo original = backupService.createBackup();
    byte[] bytes = Files.readAllBytes(tempDir.resolve("backups").resolve(original.name()));

    BackupInfo imported =
        backupService.importArchive(
            new java.io.ByteArrayInputStream(bytes), "../malicious/name.zip");

    assertThat(imported.name()).startsWith("imported-").endsWith(".zip");
    assertThat(imported.name()).doesNotContain("../");
  }

  @Test
  void importArchiveRejectsJunkWithoutMarker() throws Exception {
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("random.txt"));
      zip.write("hello".getBytes());
      zip.closeEntry();
    }

    assertThatThrownBy(
            () ->
                backupService.importArchive(
                    new java.io.ByteArrayInputStream(buffer.toByteArray()), "junk.zip"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void restoreCreatesRollbackCopyBeforeSwappingDatabase() throws Exception {
    BackupInfo info = backupService.createBackup();
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      st.executeUpdate("INSERT INTO widgets (name) VALUES ('widget-2')");
    }

    RestoreResult result = backupService.restore(info.name());

    assertThat(result.restartRequired()).isTrue();
    assertThat(result.message()).contains("pre-restore");
    try (var stream = Files.list(tempDir.resolve("rollback"))) {
      assertThat(stream.map(p -> p.getFileName().toString()).toList())
          .hasSize(1)
          .allMatch(n -> n.startsWith("opnl.db.pre-restore-"));
    }
    verify(auditLogService)
        .record(eq("BACKUP_RESTORE"), eq(AuditLogService.CAT_BACKUP), any(), any(), any());
  }

  @Test
  void restoreRejectsSnapshotWithSchemaMismatch() throws Exception {
    BackupInfo info = backupService.createBackup();
    // Stamp the live database as schema v9 while the backup snapshot has none; the restore must
    // refuse before touching anything.
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      st.executeUpdate(
          "CREATE TABLE flyway_schema_history (installed_rank INTEGER PRIMARY KEY, version TEXT)");
      st.executeUpdate(
          "INSERT INTO flyway_schema_history (installed_rank, version) VALUES (1, '9')");
    }

    assertThatThrownBy(() -> backupService.restore(info.name()))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getStatus())
        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    // No rollback copy should exist since the swap never happened.
    assertThat(tempDir.resolve("rollback")).doesNotExist();
  }

  @Test
  void restoreRejectsCorruptSnapshot() throws Exception {
    // A zip that carries a database entry which is not a valid SQLite file must be refused by the
    // snapshot validation before anything is swapped.
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("opnl.db"));
      zip.write(new byte[4096]);
      java.util.Arrays.fill(new byte[4096], (byte) 0x42);
      zip.closeEntry();
    }
    Files.createDirectories(tempDir.resolve("backups"));
    Files.write(tempDir.resolve("backups/bad-db.zip"), buffer.toByteArray());

    assertThatThrownBy(() -> backupService.restore("bad-db.zip")).isInstanceOf(ApiException.class);
    // The live database must be untouched.
    try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM widgets")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void restoreRejectsArchiveWithoutValidContent() throws Exception {
    Files.createDirectories(tempDir.resolve("backups"));
    Path junk = tempDir.resolve("backups/junk.zip");
    try (var out = Files.newOutputStream(junk);
        var zip = new java.util.zip.ZipOutputStream(out)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("random.txt"));
      zip.write("hello".getBytes());
      zip.closeEntry();
    }

    assertThatThrownBy(() -> backupService.restore("junk.zip")).isInstanceOf(ApiException.class);
  }

  @Test
  void restoreRemovesStaleWalSidecarsSoTheSwappedDatabaseStaysClean() throws Exception {
    // Keep a connection open so the -wal/-shm sidecars survive, mirroring the live backend.
    try (Connection keepOpen = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement st = keepOpen.createStatement()) {
      st.execute("PRAGMA journal_mode=WAL");
      st.executeUpdate("INSERT INTO widgets (name) VALUES ('widget-2')");
      assertThat(dbPath.resolveSibling("opnl.db-wal")).exists();

      BackupInfo info = backupService.createBackup();

      try (Connection conn = dataSource.getConnection();
          Statement st2 = conn.createStatement()) {
        st2.executeUpdate("INSERT INTO widgets (name) VALUES ('widget-3')");
      }

      backupService.restore(info.name());
    }

    assertThat(dbPath.resolveSibling("opnl.db-wal")).doesNotExist();
    assertThat(dbPath.resolveSibling("opnl.db-shm")).doesNotExist();
    // The restored main file is the clean snapshot, readable by a fresh connection.
    try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("ok");
    }
    try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM widgets")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(2);
    }
  }

  // ---- helpers ------------------------------------------------------------

  private static void createDatabase(Path path) throws Exception {
    try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement st = conn.createStatement()) {
      st.executeUpdate("CREATE TABLE widgets (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)");
      st.executeUpdate("INSERT INTO widgets (name) VALUES ('widget-1')");
    }
  }

  private static void extract(Path archive, Path target) throws Exception {
    try (var fileIn = Files.newInputStream(archive);
        var zip = new java.util.zip.ZipInputStream(fileIn)) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        Path resolved = target.resolve(entry.getName()).normalize();
        Files.createDirectories(resolved.getParent());
        Files.copy(zip, resolved);
      }
    }
  }

  private static void deleteRecursively(Path dir) {
    try (var stream = Files.walk(dir)) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (Exception ignored) {
      // best-effort cleanup
    }
  }
}
