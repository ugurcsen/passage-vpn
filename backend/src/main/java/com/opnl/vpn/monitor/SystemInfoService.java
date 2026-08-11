package com.opnl.vpn.monitor;

import com.opnl.vpn.api.admin.SystemInfoDto;
import com.opnl.vpn.config.OpnlProperties;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/**
 * Reads host CPU, memory and disk figures from the JVM's OS bean and the filesystem. Values are
 * best-effort: unavailable metrics (e.g. CPU load on some platforms) surface as {@code -1}.
 */
@Service
public class SystemInfoService {

  private final OpnlProperties properties;

  public SystemInfoService(OpnlProperties properties) {
    this.properties = properties;
  }

  public SystemInfoDto systemInfo() {
    double cpuLoadPercent = -1;
    long totalMemory = 0;
    long freeMemory = 0;
    OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
      cpuLoadPercent = round(sunOs.getCpuLoad() * 100.0);
      totalMemory = sunOs.getTotalMemorySize();
      freeMemory = sunOs.getFreeMemorySize();
    }

    long diskTotal = 0;
    long diskFree = 0;
    try {
      FileStore store = Files.getFileStore(Path.of(properties.dataDir()).toAbsolutePath());
      diskTotal = store.getTotalSpace();
      diskFree = store.getUsableSpace();
    } catch (IOException ignored) {
      // data dir not resolvable yet; report zero
    }

    return new SystemInfoDto(
        cpuLoadPercent, totalMemory, freeMemory, diskTotal, diskFree,
        Runtime.getRuntime().availableProcessors());
  }

  private static double round(double value) {
    return value < 0 ? -1 : Math.round(value * 100.0) / 100.0;
  }
}
