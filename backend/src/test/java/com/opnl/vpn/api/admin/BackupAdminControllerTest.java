package com.opnl.vpn.api.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opnl.vpn.backup.BackupService;
import com.opnl.vpn.common.GlobalExceptionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the backup admin endpoints. */
class BackupAdminControllerTest {

  private BackupService backupService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    backupService = mock(BackupService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new BackupAdminController(backupService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listReturnsBackupInfos() throws Exception {
    when(backupService.listBackups())
        .thenReturn(List.of(new BackupInfo("opnl-backup-20260101.zip", 42, Instant.now())));

    mvc.perform(get("/api/admin/backups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("opnl-backup-20260101.zip"))
        .andExpect(jsonPath("$[0].sizeBytes").value(42));
  }

  @Test
  void createDelegatesToService() throws Exception {
    when(backupService.createBackup())
        .thenReturn(new BackupInfo("opnl-backup-20260101.zip", 42, Instant.now()));

    mvc.perform(post("/api/admin/backups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("opnl-backup-20260101.zip"));
  }

  @Test
  void downloadStreamsArchiveWithAttachmentHeader() throws Exception {
    Path file = Files.createTempFile("backup", ".zip");
    Files.writeString(file, "archive-bytes");
    when(backupService.resolveBackup("opnl-backup-20260101.zip")).thenReturn(file);

    mvc.perform(get("/api/admin/backups/opnl-backup-20260101.zip/download"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition", "attachment; filename=\"opnl-backup-20260101.zip\""));
  }

  @Test
  void restoreDelegatesToService() throws Exception {
    when(backupService.restore("opnl-backup-20260101.zip"))
        .thenReturn(new RestoreResult(true, "Restored"));

    mvc.perform(post("/api/admin/backups/opnl-backup-20260101.zip/restore"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restartRequired").value(true))
        .andExpect(jsonPath("$.message").value("Restored"));
    verify(backupService).restore("opnl-backup-20260101.zip");
  }
}
