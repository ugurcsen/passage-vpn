package com.passagevpn.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.passagevpn.api.admin.PreflightCheck;
import com.passagevpn.api.admin.PreflightResult;
import com.passagevpn.api.admin.ReloadResult;
import com.passagevpn.audit.AuditLogService;
import com.passagevpn.common.ApiException;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.monitor.MgmtClientManager;
import com.passagevpn.monitor.MgmtStatus;
import com.passagevpn.network.Daemon;
import com.passagevpn.network.DaemonService;
import com.passagevpn.network.ServerConfig;
import com.passagevpn.setting.SettingsService;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Unit tests for the maintenance service using a real temp SQLite database for the preflight. */
class MaintenanceServiceTest {

  private Path tempDir;
  private Path dbPath;
  private HikariDataSource dataSource;
  private MockEnvironment environment;
  private PassageProperties properties;
  private DaemonService daemonService;
  private MgmtClientManager mgmtClientManager;
  private SettingsService settingsService;
  private ConfigSmokeTester configSmokeTester;
  private ApplicationRestarter restarter;
  private AuditLogService auditLogService;
  private MaintenanceService maintenanceService;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("passage-maintenance-test");
    dbPath = tempDir.resolve("passage.db");
    try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement st = conn.createStatement()) {
      st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      st.execute(
          "CREATE TABLE flyway_schema_history (installed_rank INTEGER PRIMARY KEY, version TEXT)");
      st.execute("INSERT INTO flyway_schema_history (installed_rank, version) VALUES (1, '14')");
    }

    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl("jdbc:sqlite:" + dbPath);
    dataSource.setMaximumPoolSize(2);

    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.datasource.url", "jdbc:sqlite:" + dbPath);
    environment.setProperty("passage.data-dir", tempDir.toString());
    this.environment = environment;

    PassageProperties properties =
        new PassageProperties(
            tempDir.toString(),
            "OpenVPN Panel",
            "secret",
            null,
            null,
            null,
            new PassageProperties.OpenVpn(
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
    this.properties = properties;

    Files.createDirectories(tempDir.resolve("pki"));
    Files.createDirectories(tempDir.resolve("config"));
    for (String file : List.of("ca.crt", "server.crt", "server.key", "ta.key", "crl.pem")) {
      Files.writeString(tempDir.resolve("pki").resolve(file), "TEST");
    }
    Files.writeString(tempDir.resolve("config/daemon-0.conf"), "port 1194");

    daemonService = mock(DaemonService.class);
    mgmtClientManager = mock(MgmtClientManager.class);
    settingsService = mock(SettingsService.class);
    configSmokeTester = mock(ConfigSmokeTester.class);
    restarter = mock(ApplicationRestarter.class);
    auditLogService = mock(AuditLogService.class);

    when(daemonService.list())
        .thenReturn(
            List.of(
                Daemon.builder()
                    .id("d1")
                    .daemonIndex(0)
                    .port(1194)
                    .proto(ServerConfig.Protocol.udp)
                    .subnet("10.8.0.0")
                    .subnetMask("255.255.255.0")
                    .enabled(true)
                    .createdAt(Instant.now())
                    .build()));

    maintenanceService =
        new MaintenanceService(
            dataSource,
            environment,
            settingsService,
            daemonService,
            mgmtClientManager,
            configSmokeTester,
            properties,
            auditLogService,
            restarter);
  }

  @AfterEach
  void tearDown() {
    dataSource.close();
  }

  @Test
  void preflightPassesWhenEverythingIsGreen() {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.PASS, "ok"));

    PreflightResult result = maintenanceService.preflight();

    assertThat(result.passed()).isTrue();
    assertThat(result.checks())
        .extracting(PreflightCheck::status)
        .doesNotContain(PreflightCheck.Status.FAIL);
  }

  @Test
  void preflightFailsOnBrokenDaemonConfig() {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(
            new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.FAIL, "Options error"));

    PreflightResult result = maintenanceService.preflight();

    assertThat(result.passed()).isFalse();
    assertThat(result.checks())
        .anyMatch(c -> c.status() == PreflightCheck.Status.FAIL && c.name().equals("daemon-0"));
  }

  @Test
  void preflightWarnDoesNotBlock() {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.WARN, "exited 1"));

    PreflightResult result = maintenanceService.preflight();

    assertThat(result.passed()).isTrue();
    assertThat(result.checks()).anyMatch(c -> c.status() == PreflightCheck.Status.WARN);
  }

  @Test
  void preflightDetectsCorruptDatabase() throws Exception {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.PASS, "ok"));
    // Break the SQLite header magic so a fresh connection cannot read the file.
    dataSource.close();
    byte[] bytes = Files.readAllBytes(dbPath);
    bytes[0] ^= 0x01;
    Files.write(dbPath, bytes);

    HikariDataSource corruptDs = new HikariDataSource();
    corruptDs.setJdbcUrl("jdbc:sqlite:" + dbPath);
    corruptDs.setMaximumPoolSize(1);
    MaintenanceService corruptService =
        new MaintenanceService(
            corruptDs,
            environment,
            settingsService,
            daemonService,
            mgmtClientManager,
            configSmokeTester,
            properties,
            auditLogService,
            restarter);
    try {
      PreflightResult result = corruptService.preflight();

      assertThat(result.passed()).isFalse();
      assertThat(result.checks())
          .anyMatch(c -> c.name().equals("database") && c.status() == PreflightCheck.Status.FAIL);
    } finally {
      corruptDs.close();
    }
  }

  @Test
  void preflightFailsOnInvalidSettingValue() {
    when(settingsService.serverSettings())
        .thenReturn(Map.of("network_mode", "nope", "syslog_port", "not-a-number"));
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.PASS, "ok"));

    PreflightResult result = maintenanceService.preflight();

    assertThat(result.passed()).isFalse();
    assertThat(result.checks())
        .anyMatch(c -> c.name().equals("settings") && c.status() == PreflightCheck.Status.FAIL);
  }

  @Test
  void restartBackendSchedulesRestartAfterGreenPreflight() {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.PASS, "ok"));

    var result = maintenanceService.restartBackend();

    assertThat(result.message()).contains("restart");
    verify(restarter).scheduleRestart();
    verify(auditLogService)
        .record(eq("SYSTEM_RESTART"), eq(AuditLogService.CAT_SYSTEM), any(), any(), any());
  }

  @Test
  void restartBackendRefusedWhenPreflightFails() {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(
            new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.FAIL, "Options error"));

    assertThatThrownBy(() -> maintenanceService.restartBackend())
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getStatus())
        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    verify(restarter, never()).scheduleRestart();
  }

  @Test
  void reloadDaemonsSignalsEachEnabledDaemonAndVerifies() {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.PASS, "ok"));
    when(mgmtClientManager.signal(null, 0, "SIGHUP")).thenReturn(true);
    MgmtStatus status = mock(MgmtStatus.class);
    when(mgmtClientManager.status(null, 0)).thenReturn(status);

    ReloadResult result = maintenanceService.reloadDaemons();

    assertThat(result.signaled()).isEqualTo(1);
    assertThat(result.total()).isEqualTo(1);
    assertThat(result.failed()).isEmpty();
    verify(mgmtClientManager).signal(null, 0, "SIGHUP");
    verify(auditLogService)
        .record(eq("SYSTEM_RELOAD"), eq(AuditLogService.CAT_SYSTEM), any(), any(), any());
  }

  @Test
  void reloadDaemonsReportsUnreachableDaemonsAsFailed() {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    when(configSmokeTester.test(any(Path.class)))
        .thenReturn(new ConfigSmokeTester.Result(ConfigSmokeTester.Result.Status.PASS, "ok"));
    when(mgmtClientManager.signal(null, 0, "SIGHUP")).thenReturn(true);
    when(mgmtClientManager.status(null, 0)).thenReturn(null);

    ReloadResult result = maintenanceService.reloadDaemons();

    assertThat(result.signaled()).isEqualTo(1);
    assertThat(result.failed()).containsExactly(0);
  }

  @Test
  void vacuumCompactsTheSqliteDatabase() throws Exception {
    maintenanceService.vacuumSqlite();

    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement();
        java.sql.ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("ok");
    }
  }

  @Test
  void vacuumIsANoopOnNonSqliteDatasources() {
    MockEnvironment postgres = new MockEnvironment();
    postgres.setProperty("spring.datasource.url", "jdbc:postgresql://localhost/opnl");
    MaintenanceService svc =
        new MaintenanceService(
            dataSource,
            postgres,
            settingsService,
            daemonService,
            mgmtClientManager,
            configSmokeTester,
            properties,
            auditLogService,
            restarter);

    svc.vacuumSqlite(); // must not touch the SQLite database
  }
}
