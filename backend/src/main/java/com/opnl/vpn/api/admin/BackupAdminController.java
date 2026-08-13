package com.opnl.vpn.api.admin;

import com.opnl.vpn.backup.BackupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin backup management: create, list, download and restore archives. */
@RestController
@RequestMapping("/api/admin/backups")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Backups", description = "Backup archives and restore (admin-only)")
public class BackupAdminController {

  private final BackupService backupService;

  public BackupAdminController(BackupService backupService) {
    this.backupService = backupService;
  }

  @GetMapping
  @Operation(summary = "List stored backups, newest first")
  public java.util.List<BackupInfo> list() {
    return backupService.listBackups();
  }

  @PostMapping
  @Operation(summary = "Create a new backup archive")
  public BackupInfo create() {
    return backupService.createBackup();
  }

  @GetMapping("/{name}/download")
  @Operation(summary = "Download a backup archive")
  public ResponseEntity<Resource> download(@PathVariable String name) {
    Path file = backupService.resolveBackup(name);
    Resource resource = new FileSystemResource(file);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .body(resource);
  }

  @PostMapping("/{name}/restore")
  @Operation(summary = "Restore a backup archive (database swap requires a restart)")
  public RestoreResult restore(@PathVariable String name) {
    return backupService.restore(name);
  }
}
