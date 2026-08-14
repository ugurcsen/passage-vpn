package com.opnl.vpn.system;

import com.opnl.vpn.api.admin.PreflightCheck;
import com.opnl.vpn.api.admin.PreflightResult;
import com.opnl.vpn.api.admin.ReloadResult;
import com.opnl.vpn.api.admin.RestartResult;
import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ApiException;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.monitor.MgmtClientManager;
import com.opnl.vpn.network.Daemon;
import com.opnl.vpn.network.DaemonService;
import com.opnl.vpn.setting.SettingValidator;
import com.opnl.vpn.setting.SettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Maintenance operations behind the Danger Zone: a preflight gate, a graceful backend restart and
 * an OpenVPN daemon reload. Restart/reload refuse to run (HTTP 409) while any preflight check
 * FAILs.
 */
@Slf4j
@Service
public class MaintenanceService {

  private final DataSource dataSource;
  private final Environment environment;
  private final SettingsService settingsService;
  private final DaemonService daemonService;
  private final MgmtClientManager mgmtClientManager;
  private final ConfigSmokeTester configSmokeTester;
  private final OpnlProperties properties;
  private final AuditLogService auditLogService;
  private final ApplicationRestarter restarter;

  public MaintenanceService(
      DataSource dataSource,
      Environment environment,
      SettingsService settingsService,
      DaemonService daemonService,
      MgmtClientManager mgmtClientManager,
      ConfigSmokeTester configSmokeTester,
      OpnlProperties properties,
      AuditLogService auditLogService,
      ApplicationRestarter restarter) {
    this.dataSource = dataSource;
    this.environment = environment;
    this.settingsService = settingsService;
    this.daemonService = daemonService;
    this.mgmtClientManager = mgmtClientManager;
    this.configSmokeTester = configSmokeTester;
    this.properties = properties;
    this.auditLogService = auditLogService;
    this.restarter = restarter;
  }

  public PreflightResult preflight() {
    List<PreflightCheck> checks = new ArrayList<>();
    checks.add(dbIntegrity());
    checks.add(settingsValid());
    checks.addAll(daemonConfigs());
    checks.add(pkiSanity());
    boolean passed = checks.stream().noneMatch(c -> c.status() == PreflightCheck.Status.FAIL);
    return new PreflightResult(passed, checks);
  }

  public RestartResult restartBackend() {
    requirePreflight();
    restarter.scheduleRestart();
    auditLogService.record("SYSTEM_RESTART", AuditLogService.CAT_SYSTEM, null, "system", Map.of());
    return new RestartResult("Backend is restarting; the UI will reconnect shortly");
  }

  public ReloadResult reloadDaemons() {
    requirePreflight();
    List<Daemon> enabled = daemonService.list().stream().filter(Daemon::isEnabled).toList();
    int signaled = 0;
    List<Integer> failed = new ArrayList<>();
    for (Daemon daemon : enabled) {
      if (mgmtClientManager.signal(daemon.getNodeId(), daemon.getDaemonIndex(), "SIGHUP")) {
        signaled++;
      } else {
        failed.add(daemon.getDaemonIndex());
      }
    }
    // Give the daemons a moment to reopen their management sockets after the restart, then verify.
    sleep(1500);
    for (Daemon daemon : enabled) {
      if (failed.contains(daemon.getDaemonIndex())) {
        continue;
      }
      boolean verified = false;
      for (int attempt = 0; attempt < 3 && !verified; attempt++) {
        if (mgmtClientManager.status(daemon.getNodeId(), daemon.getDaemonIndex()) != null) {
          verified = true;
        } else {
          sleep(1000);
        }
      }
      if (!verified) {
        failed.add(daemon.getDaemonIndex());
      }
    }
    auditLogService.record(
        "SYSTEM_RELOAD",
        AuditLogService.CAT_SYSTEM,
        null,
        "system",
        Map.of("signaled", signaled, "total", enabled.size(), "failed", failed));
    return new ReloadResult(signaled, enabled.size(), failed);
  }

  private void requirePreflight() {
    PreflightResult result = preflight();
    if (!result.passed()) {
      String failed =
          result.checks().stream()
              .filter(c -> c.status() == PreflightCheck.Status.FAIL)
              .map(PreflightCheck::name)
              .reduce((a, b) -> a + ", " + b)
              .orElse("unknown");
      throw ApiException.conflict("preflight_failed", "Preflight failed: " + failed);
    }
  }

  /**
   * Reclaims disk space freed by the nightly purge jobs. {@code VACUUM} is SQLite-specific, so this
   * is a no-op on other datasources. Runs after the log/certificate purges at 03:15/03:20.
   */
  @Scheduled(cron = "0 30 3 * * *")
  public void vacuumSqlite() {
    if (!isSqlite()) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("VACUUM");
      log.info("SQLite database vacuumed");
    } catch (SQLException e) {
      log.warn("SQLite vacuum failed: {}", e.getMessage());
    }
  }

  // ---- preflight checks ----------------------------------------------------

  private PreflightCheck dbIntegrity() {
    if (!isSqlite()) {
      return new PreflightCheck(
          "database", PreflightCheck.Status.PASS, "Non-SQLite datasource; integrity not checked");
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
        rs.next();
        String result = rs.getString(1);
        if (!"ok".equals(result)) {
          return new PreflightCheck(
              "database", PreflightCheck.Status.FAIL, "SQLite integrity_check returned: " + result);
        }
      }
      try (ResultSet rs = statement.executeQuery("PRAGMA foreign_key_check")) {
        if (rs.next()) {
          return new PreflightCheck(
              "database",
              PreflightCheck.Status.FAIL,
              "SQLite foreign_key_check found violations starting at row " + rs.getLong(1));
        }
      }
      return new PreflightCheck("database", PreflightCheck.Status.PASS, "Integrity check passed");
    } catch (SQLException e) {
      return new PreflightCheck(
          "database", PreflightCheck.Status.FAIL, "Cannot run integrity check: " + e.getMessage());
    }
  }

  private PreflightCheck settingsValid() {
    try {
      Map<String, Object> settings = settingsService.serverSettings();
      for (Map.Entry<String, Object> entry : settings.entrySet()) {
        try {
          SettingValidator.validate(entry.getKey(), entry.getValue());
        } catch (ApiException e) {
          return new PreflightCheck(
              "settings", PreflightCheck.Status.FAIL, entry.getKey() + ": " + e.getMessage());
        }
      }
      return new PreflightCheck(
          "settings", PreflightCheck.Status.PASS, settings.size() + " server setting(s) valid");
    } catch (Exception e) {
      return new PreflightCheck(
          "settings", PreflightCheck.Status.FAIL, "Cannot read settings: " + e.getMessage());
    }
  }

  private List<PreflightCheck> daemonConfigs() {
    List<PreflightCheck> checks = new ArrayList<>();
    for (Daemon daemon : daemonService.list()) {
      if (!daemon.isEnabled()) {
        continue;
      }
      Path configPath =
          Path.of(properties.openvpn().configDir())
              .resolve("daemon-" + daemon.getDaemonIndex() + ".conf");
      String name = "daemon-" + daemon.getDaemonIndex();
      if (!Files.isRegularFile(configPath)) {
        checks.add(
            new PreflightCheck(name, PreflightCheck.Status.FAIL, "Config missing: " + configPath));
        continue;
      }
      ConfigSmokeTester.Result result = configSmokeTester.test(configPath);
      PreflightCheck.Status status =
          switch (result.status()) {
            case PASS -> PreflightCheck.Status.PASS;
            case WARN -> PreflightCheck.Status.WARN;
            case FAIL -> PreflightCheck.Status.FAIL;
          };
      checks.add(new PreflightCheck(name, status, result.detail()));
    }
    if (checks.isEmpty()) {
      checks.add(
          new PreflightCheck(
              "daemons", PreflightCheck.Status.PASS, "No enabled daemon configs to check"));
    }
    return checks;
  }

  private PreflightCheck pkiSanity() {
    Path pki = Path.of(properties.openvpn().pkiDir());
    for (String file : List.of("ca.crt", "server.crt", "server.key", "ta.key", "crl.pem")) {
      if (!Files.isRegularFile(pki.resolve(file))) {
        return new PreflightCheck(
            "pki", PreflightCheck.Status.FAIL, "Missing " + file + " in " + pki);
      }
    }
    try {
      Process process =
          new ProcessBuilder(
                  "openssl",
                  "x509",
                  "-in",
                  pki.resolve("server.crt").toString(),
                  "-noout",
                  "-checkend",
                  "0")
              .redirectErrorStream(true)
              .start();
      boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      if (finished && process.exitValue() == 0) {
        return new PreflightCheck(
            "pki", PreflightCheck.Status.PASS, "PKI files present; server certificate valid");
      }
      return new PreflightCheck(
          "pki", PreflightCheck.Status.WARN, "PKI files present but server certificate is expired");
    } catch (Exception e) {
      return new PreflightCheck(
          "pki", PreflightCheck.Status.WARN, "PKI files present; expiry check unavailable");
    }
  }

  private boolean isSqlite() {
    return environment.getProperty("spring.datasource.url", "").startsWith("jdbc:sqlite:");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
