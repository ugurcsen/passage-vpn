package com.passagevpn.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.passagevpn.audit.AuditLogService;
import com.passagevpn.common.ProcessRunner;
import com.passagevpn.config.PassageProperties;
import com.passagevpn.setting.SettingKeys;
import com.passagevpn.setting.SettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PostAuthHookServiceTest {

  @TempDir Path scriptsDir;

  private SettingsService settingsService;
  private PassageProperties properties;
  private ProcessRunner processRunner;
  private AuditLogService auditLogService;
  private PostAuthHookService service;

  @BeforeEach
  void setUp() {
    settingsService = mock(SettingsService.class);
    properties = mock(PassageProperties.class);
    PassageProperties.OpenVpn openVpn = mock(PassageProperties.OpenVpn.class);
    when(openVpn.scriptsDir()).thenReturn(scriptsDir.toString());
    when(properties.openvpn()).thenReturn(openVpn);
    processRunner = mock(ProcessRunner.class);
    auditLogService = mock(AuditLogService.class);
    service = new PostAuthHookService(settingsService, properties, processRunner, auditLogService);
  }

  private void scriptConfigured(String name) {
    when(settingsService.serverSettings()).thenReturn(Map.of(SettingKeys.POST_AUTH_SCRIPT, name));
  }

  @Test
  void noopWithoutConfiguredScript() {
    when(settingsService.serverSettings()).thenReturn(Map.of());
    service.run("alice", "1.2.3.4");
    verifyNoInteractions(processRunner);
  }

  @Test
  void runsPythonScriptFromScriptsDirOnSuccess() throws Exception {
    Path hook = scriptsDir.resolve("post-auth-hook.py");
    Files.writeString(hook, "#!/usr/bin/env python3\nprint('ok')\n");
    scriptConfigured("post-auth-hook.py");
    when(processRunner.run(any(List.class), any(Map.class), any(Duration.class)))
        .thenReturn(new ProcessRunner.Result(0, "", ""));

    service.run("alice", "1.2.3.4");

    ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
    verify(processRunner).run(command.capture(), env.capture(), eq(Duration.ofSeconds(10)));
    assertThat(command.getValue()).containsExactly("python3", hook.toString());
    assertThat(env.getValue())
        .containsEntry("username", "alice")
        .containsEntry("common_name", "alice")
        .containsEntry("remote_ip", "1.2.3.4");
    verify(auditLogService)
        .record(
            eq("VPN_POST_AUTH_HOOK"),
            eq(AuditLogService.CAT_AUTH),
            eq("alice"),
            eq("user"),
            any(Map.class));
  }

  @Test
  void nonZeroExitIsNonFatalAndAudited() throws Exception {
    Files.writeString(
        scriptsDir.resolve("hook.py"), "#!/usr/bin/env python3\nraise SystemExit(3)\n");
    scriptConfigured("hook.py");
    when(processRunner.run(any(List.class), any(Map.class), any(Duration.class)))
        .thenReturn(new ProcessRunner.Result(3, "", "boom"));

    assertThatCode(() -> service.run("alice", "1.2.3.4")).doesNotThrowAnyException();

    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditLogService)
        .record(
            eq("VPN_POST_AUTH_HOOK"),
            eq(AuditLogService.CAT_AUTH),
            eq("alice"),
            eq("user"),
            detail.capture());
    assertThat(detail.getValue()).containsEntry("success", false).containsEntry("exitCode", 3);
    assertThat(detail.getValue().get("error")).isEqualTo("boom");
  }

  @Test
  void exceptionIsNonFatalAndAudited() throws Exception {
    Files.writeString(scriptsDir.resolve("hook.py"), "#!/usr/bin/env python3\npass\n");
    scriptConfigured("hook.py");
    when(processRunner.run(any(List.class), any(Map.class), any(Duration.class)))
        .thenThrow(new RuntimeException("python3 not found"));

    assertThatCode(() -> service.run("alice", "1.2.3.4")).doesNotThrowAnyException();

    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditLogService)
        .record(
            eq("VPN_POST_AUTH_HOOK"),
            eq(AuditLogService.CAT_AUTH),
            eq("alice"),
            eq("user"),
            detail.capture());
    assertThat(detail.getValue()).containsEntry("success", false).containsEntry("exitCode", -1);
  }

  @Test
  void missingScriptIsNonFatalAndNoProcessRun() {
    scriptConfigured("does-not-exist.py");
    service.run("alice", "1.2.3.4");
    verifyNoInteractions(processRunner);
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditLogService)
        .record(
            eq("VPN_POST_AUTH_HOOK"),
            eq(AuditLogService.CAT_AUTH),
            eq("alice"),
            eq("user"),
            detail.capture());
    assertThat(detail.getValue()).containsEntry("success", false);
  }

  @Test
  void supportsAbsoluteScriptPath() throws Exception {
    Path hook = scriptsDir.resolve("absolute-hook.py");
    Files.writeString(hook, "#!/usr/bin/env python3\npass\n");
    scriptConfigured(hook.toString());
    when(processRunner.run(any(List.class), any(Map.class), any(Duration.class)))
        .thenReturn(new ProcessRunner.Result(0, "", ""));

    service.run("alice", "1.2.3.4");

    ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
    verify(processRunner).run(command.capture(), any(Map.class), any(Duration.class));
    assertThat(command.getValue()).containsExactly("python3", hook.toString());
  }

  @Test
  void honorsConfiguredTimeout() throws Exception {
    Files.writeString(scriptsDir.resolve("hook.py"), "#!/usr/bin/env python3\npass\n");
    when(settingsService.serverSettings())
        .thenReturn(
            Map.of(
                SettingKeys.POST_AUTH_SCRIPT, "hook.py", SettingKeys.POST_AUTH_TIMEOUT_SECONDS, 5));
    when(processRunner.run(any(List.class), any(Map.class), any(Duration.class)))
        .thenReturn(new ProcessRunner.Result(0, "", ""));

    service.run("alice", "1.2.3.4");

    verify(processRunner).run(any(List.class), any(Map.class), eq(Duration.ofSeconds(5)));
  }

  @Test
  void invalidTimeoutFallsBackToDefault() throws Exception {
    Files.writeString(scriptsDir.resolve("hook.py"), "#!/usr/bin/env python3\npass\n");
    when(settingsService.serverSettings())
        .thenReturn(
            Map.of(
                SettingKeys.POST_AUTH_SCRIPT,
                "hook.py",
                SettingKeys.POST_AUTH_TIMEOUT_SECONDS,
                9999));
    when(processRunner.run(any(List.class), any(Map.class), any(Duration.class)))
        .thenReturn(new ProcessRunner.Result(0, "", ""));

    service.run("alice", "1.2.3.4");

    verify(processRunner).run(any(List.class), any(Map.class), eq(Duration.ofSeconds(10)));
  }

  @Test
  void blankScriptSettingIsNoop() {
    scriptConfigured("   ");
    service.run("alice", "1.2.3.4");
    verifyNoInteractions(processRunner);
    verify(auditLogService, never()).record(any(), any(), any(), any(), any());
  }
}
