package com.opnl.vpn.auth;

import com.opnl.vpn.audit.AuditLogService;
import com.opnl.vpn.common.ProcessRunner;
import com.opnl.vpn.config.OpnlProperties;
import com.opnl.vpn.setting.SettingKeys;
import com.opnl.vpn.setting.SettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional post-auth hook: after a successful VPN login the configured script is executed with the
 * authenticated account as environment. The hook is best-effort — a missing script, non-zero exit
 * or timeout never rejects a connection that already passed credential verification; the outcome is
 * recorded in the audit trail instead.
 */
@Slf4j
@Service
public class PostAuthHookService {

  private static final int DEFAULT_TIMEOUT_SECONDS = 10;
  private static final int MAX_ERROR_CHARS = 500;

  private final SettingsService settingsService;
  private final OpnlProperties properties;
  private final ProcessRunner processRunner;
  private final AuditLogService auditLogService;

  public PostAuthHookService(
      SettingsService settingsService,
      OpnlProperties properties,
      ProcessRunner processRunner,
      AuditLogService auditLogService) {
    this.settingsService = settingsService;
    this.properties = properties;
    this.processRunner = processRunner;
    this.auditLogService = auditLogService;
  }

  /**
   * Runs the configured post-auth hook for a successful VPN login. No-op when no script is
   * configured; never throws.
   *
   * @param username authenticated account name (also used as common name, certs are issued with the
   *     username as CN)
   * @param remoteIp client source address
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void run(String username, String remoteIp) {
    String script = scriptSetting();
    if (script == null || script.isBlank()) {
      return;
    }
    Path scriptPath = resolveScript(script);
    if (!Files.isRegularFile(scriptPath)) {
      log.warn("Post-auth hook not found: {} (configured as '{}')", scriptPath, script);
      audit(false, script, username, remoteIp, -1, "script not found");
      return;
    }
    Duration timeout = timeoutSetting();
    Map<String, String> env = new LinkedHashMap<>();
    env.put("username", username == null ? "" : username);
    env.put("common_name", username == null ? "" : username);
    env.put("remote_ip", remoteIp == null ? "" : remoteIp);
    List<String> command =
        scriptPath.toString().endsWith(".py")
            ? List.of("python3", scriptPath.toString())
            : List.of(scriptPath.toString());
    try {
      ProcessRunner.Result result = processRunner.run(command, env, timeout);
      boolean success = result.exitCode() == 0;
      String error = success ? null : truncate(result.stderr());
      if (!success) {
        log.warn(
            "Post-auth hook {} exited {} for user {}: {}",
            script,
            result.exitCode(),
            username,
            error);
      } else {
        log.debug("Post-auth hook {} ran successfully for user {}", script, username);
      }
      audit(success, script, username, remoteIp, result.exitCode(), error);
    } catch (Exception e) {
      log.warn("Post-auth hook {} failed for user {}: {}", script, username, e.getMessage());
      audit(false, script, username, remoteIp, -1, truncate(e.getMessage()));
    }
  }

  // ---- helpers ------------------------------------------------------------

  private String scriptSetting() {
    Object raw = settingsService.serverSettings().get(SettingKeys.POST_AUTH_SCRIPT);
    return raw == null ? null : String.valueOf(raw).trim();
  }

  private Path resolveScript(String script) {
    if (script.startsWith("/")) {
      return Path.of(script);
    }
    return Path.of(properties.openvpn().scriptsDir()).resolve(script);
  }

  private Duration timeoutSetting() {
    Object raw = settingsService.serverSettings().get(SettingKeys.POST_AUTH_TIMEOUT_SECONDS);
    if (raw instanceof Number number) {
      long seconds = number.longValue();
      if (seconds >= 1 && seconds <= 120) {
        return Duration.ofSeconds(seconds);
      }
    }
    if (raw instanceof String s) {
      try {
        long seconds = Long.parseLong(s.trim());
        if (seconds >= 1 && seconds <= 120) {
          return Duration.ofSeconds(seconds);
        }
      } catch (NumberFormatException ignored) {
        // fall through to the default
      }
    }
    return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
  }

  private void audit(
      boolean success,
      String script,
      String username,
      String remoteIp,
      int exitCode,
      String error) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("script", script);
    detail.put("username", username);
    detail.put("remoteIp", remoteIp);
    detail.put("exitCode", exitCode);
    detail.put("success", success);
    if (error != null && !error.isBlank()) {
      detail.put("error", error);
    }
    auditLogService.record(
        "VPN_POST_AUTH_HOOK", AuditLogService.CAT_AUTH, username, "user", detail);
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS) + "…";
  }
}
