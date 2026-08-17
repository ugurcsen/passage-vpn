package com.passagevpn.api.admin;

import com.passagevpn.backup.BackupService;
import com.passagevpn.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

  @PostMapping("/import")
  @Operation(summary = "Import a backup archive uploaded from the UI")
  public BackupInfo importBackup(@RequestParam("file") MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("empty_file", "No backup file provided");
    }
    try (InputStream input = file.getInputStream()) {
      return backupService.importArchive(input, file.getOriginalFilename());
    } catch (IOException e) {
      throw ApiException.internal(
          "import_failed", "Cannot read uploaded backup: " + e.getMessage());
    }
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
