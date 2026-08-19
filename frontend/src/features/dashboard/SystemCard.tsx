import { Paper, Skeleton, Typography } from "@mui/material";
import MemoryIcon from "@mui/icons-material/Memory";
import SpeedIcon from "@mui/icons-material/Speed";
import StorageIcon from "@mui/icons-material/Storage";
import type { SystemInfo } from "@/lib/api";
import { ResourceBar } from "./ResourceBar";
import { formatBytes } from "./helpers";

export function SystemCard({ system, loading }: { system: SystemInfo | null; loading: boolean }) {
  if (loading && !system) {
    return (
      <Paper sx={{ p: 3 }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
          System
        </Typography>
        <Skeleton height={120} />
      </Paper>
    );
  }
  if (!system) {
    return (
      <Paper sx={{ p: 3 }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
          System
        </Typography>
        <Typography variant="body2" color="text.secondary">
          No system data yet.
        </Typography>
      </Paper>
    );
  }
  const ramUsed = system.totalMemory > 0 ? Math.max(0, system.totalMemory - system.freeMemory) : 0;
  const ramPct = system.totalMemory > 0 ? (ramUsed / system.totalMemory) * 100 : 0;
  const diskUsed = system.diskTotal > 0 ? Math.max(0, system.diskTotal - system.diskFree) : 0;
  const diskPct = system.diskTotal > 0 ? (diskUsed / system.diskTotal) * 100 : 0;
  return (
    <Paper sx={{ p: 3 }}>
      <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
        System
      </Typography>
      <ResourceBar
        label="CPU"
        percent={system.cpuLoadPercent}
        detail={`${Math.round(system.cpuLoadPercent)}%`}
        icon={<SpeedIcon fontSize="small" />}
        color="info"
      />
      <ResourceBar
        label="Memory"
        percent={ramPct}
        detail={`${formatBytes(ramUsed)} / ${formatBytes(system.totalMemory)}`}
        icon={<MemoryIcon fontSize="small" />}
        color="primary"
      />
      <ResourceBar
        label="Disk"
        percent={diskPct}
        detail={`${formatBytes(diskUsed)} / ${formatBytes(system.diskTotal)}`}
        icon={<StorageIcon fontSize="small" />}
        color="warning"
      />
      <Typography variant="caption" color="text.secondary">
        {system.availableProcessors} logical CPUs
      </Typography>
    </Paper>
  );
}
