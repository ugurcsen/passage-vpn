package com.passagevpn.api.admin;

import java.util.List;
import java.util.Map;

/** Snapshot of the running configuration: settings, daemons, PKI inventory and version info. */
public record ConfigReportDto(
    String brand,
    String version,
    String generatedAt,
    String dbType,
    DataDirs dataDirs,
    Map<String, Object> serverSettings,
    List<DaemonSummary> daemons,
    PkiInventory pki,
    long users,
    long groups) {

  public record DataDirs(String pki, String ccd, String config, String logs) {}

  public record DaemonSummary(int index, String name, int port, String proto, boolean enabled) {}

  public record PkiInventory(
      long total, long valid, long revoked, long expired, long expiringSoon) {}
}
