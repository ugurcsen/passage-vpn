import {
  Alert,
  Avatar,
  Box,
  Chip,
  Grid,
  LinearProgress,
  Paper,
  Skeleton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { LineChart } from "@mui/x-charts/LineChart";
import GroupsIcon from "@mui/icons-material/Groups";
import LanIcon from "@mui/icons-material/Lan";
import MemoryIcon from "@mui/icons-material/Memory";
import PeopleIcon from "@mui/icons-material/People";
import SpeedIcon from "@mui/icons-material/Speed";
import StorageIcon from "@mui/icons-material/Storage";
import VerifiedUserIcon from "@mui/icons-material/VerifiedUser";
import type { ReactNode } from "react";
import { api, endpoints, type DashboardStats, type DaemonHealth, type SystemInfo } from "@/lib/api";
import { useLiveStatus } from "@/hooks/useLiveStatus";

function StatCard({
  title,
  value,
  loading,
  icon,
  color,
}: {
  title: string;
  value: number;
  loading: boolean;
  icon: ReactNode;
  color: "primary" | "secondary" | "success" | "warning" | "error" | "info";
}) {
  return (
    <Grid item xs={12} sm={6} md={3}>
      <Paper sx={{ p: 2.5, display: "flex", alignItems: "center", gap: 2, height: "100%" }}>
        <Avatar sx={{ bgcolor: `${color}.main`, width: 46, height: 46 }}>
          <Box component="span" sx={{ display: "flex", color: "#fff" }}>
            {icon}
          </Box>
        </Avatar>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" color="text.secondary" noWrap>
            {title}
          </Typography>
          {loading ? (
            <Skeleton variant="text" width="50%" sx={{ fontSize: "2rem" }} />
          ) : (
            <Typography variant="h4" fontWeight={700}>
              {value}
            </Typography>
          )}
        </Box>
      </Paper>
    </Grid>
  );
}

function formatSince(iso: string) {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m ago`;
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  return `${(bytes / 1024 ** i).toFixed(1)} ${units[i]}`;
}

/** Rate with an explicit "no data" marker (used by the live chips). */
function formatRate(bytesPerSec: number | null | undefined) {
  if (bytesPerSec === null || bytesPerSec === undefined || !Number.isFinite(bytesPerSec) || bytesPerSec <= 0) {
    return "—";
  }
  return formatRateLabel(bytesPerSec);
}

/** Always-formatted rate (used by chart ticks and tooltips; 0 renders as "0 B/s"). */
function formatRateLabel(bytesPerSec: number) {
  if (bytesPerSec < 1024) return `${bytesPerSec.toFixed(0)} B/s`;
  if (bytesPerSec < 1024 ** 2) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSec / 1024 ** 2).toFixed(2)} MB/s`;
}

/** Compact clock label for the x-axis: "3:04 PM", no seconds. */
function formatTime(value: number | Date) {
  return new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function TrafficChart({ history }: { history: { at: string; bytesInPerSec: number; bytesOutPerSec: number }[] }) {
  if (!history.length) {
    return (
      <Typography variant="body2" color="text.secondary">
        Collecting traffic data… (the chart appears after a few samples)
      </Typography>
    );
  }
  const seriesValueFormatter = (value: number | null) => (value == null ? "—" : formatRateLabel(value));
  return (
    <Box
      data-testid="traffic-chart"
      sx={{
        width: "100%",
        "& .MuiLineElement-root": { strokeWidth: 2.5 },
        "& .MuiAreaElement-root": { opacity: 0.25 },
      }}
    >
      <LineChart
        height={280}
        skipAnimation
        grid={{ horizontal: true }}
        margin={{ top: 44, right: 16, bottom: 30, left: 72 }}
        xAxis={[
          {
            data: history.map((p) => new Date(p.at)),
            scaleType: "time",
            valueFormatter: (value: number | Date) => formatTime(value),
          },
        ]}
        yAxis={[
          {
            valueFormatter: (value: number) => formatRateLabel(value),
          },
        ]}
        series={[
          {
            data: history.map((p) => p.bytesOutPerSec),
            label: "Download",
            color: "#66bb6a",
            area: true,
            baseline: "min",
            showMark: false,
            valueFormatter: seriesValueFormatter,
          },
          {
            data: history.map((p) => p.bytesInPerSec),
            label: "Upload",
            color: "#42a5f5",
            area: true,
            baseline: "min",
            showMark: false,
            valueFormatter: seriesValueFormatter,
          },
        ]}
        slotProps={{
          legend: {
            position: { vertical: "top", horizontal: "middle" },
            direction: "row",
            itemMarkWidth: 14,
            itemMarkHeight: 14,
          },
        }}
      />
    </Box>
  );
}

function ResourceBar({
  label,
  percent,
  detail,
  icon,
  color,
}: {
  label: string;
  percent: number;
  detail: string;
  icon: ReactNode;
  color: "primary" | "success" | "warning" | "info";
}) {
  return (
    <Box sx={{ mb: 2 }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", mb: 0.5 }}>
        <Stack direction="row" spacing={0.75} alignItems="center">
          <Box component="span" sx={{ display: "flex", color: "text.secondary" }}>
            {icon}
          </Box>
          <Typography variant="body2">{label}</Typography>
        </Stack>
        <Typography variant="body2" color="text.secondary">
          {detail}
        </Typography>
      </Box>
      <LinearProgress
        variant="determinate"
        value={Math.min(100, Math.max(0, percent))}
        color={color}
        sx={{ height: 6, borderRadius: 3 }}
      />
    </Box>
  );
}

function SystemCard({ system, loading }: { system: SystemInfo | null; loading: boolean }) {
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

function DaemonChip({ daemon }: { daemon: DaemonHealth }) {
  const ok = daemon.mgmtReachable && daemon.enabled && daemon.configPresent;
  const status = !daemon.enabled
    ? "Disabled"
    : !daemon.configPresent
      ? "Config missing"
      : daemon.mgmtReachable
        ? "Online"
        : "Management down";
  const label = `#${daemon.index} ${daemon.name ?? "unnamed"}`;
  return (
    <Tooltip title={`${label} · ${daemon.proto.toUpperCase()} :${daemon.port} · ${status}${daemon.dco ? " · DCO" : ""}`}>
      <Chip
        size="small"
        color={ok ? "success" : daemon.enabled ? "warning" : "default"}
        variant={ok ? "filled" : "outlined"}
        label={
          <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
            <Box
              component="span"
              sx={{
                width: 8,
                height: 8,
                borderRadius: "50%",
                bgcolor: ok ? "success.contrastText" : daemon.enabled ? "warning.main" : "text.disabled",
                display: "inline-block",
              }}
            />
            <Box component="span" sx={{ fontWeight: 500 }}>
              {label}
            </Box>
            <Box component="span" sx={{ fontSize: "0.7rem", opacity: 0.9 }}>
              {daemon.proto.toUpperCase()}:{daemon.port}
            </Box>
            {daemon.dco ? (
              <Box
                component="span"
                sx={{
                  fontSize: "0.65rem",
                  px: 0.5,
                  py: 0.1,
                  borderRadius: 1,
                  bgcolor: "secondary.dark",
                  color: "secondary.contrastText",
                  lineHeight: 1.2,
                }}
                title="Data Channel Offload (kernel)"
              >
                DCO
              </Box>
            ) : null}
          </Box>
        }
      />
    </Tooltip>
  );
}

/** Dashboard: live stat cards, real-time traffic chart, host system card and daemon health. */
export function DashboardPage() {
  const { data, isLoading, error } = useQuery<DashboardStats>({
    queryKey: ["admin-dashboard"],
    queryFn: () => api<DashboardStats>(endpoints.dashboard),
    refetchInterval: 15_000,
  });
  const { snapshot, connected } = useLiveStatus();

  const history = snapshot?.history ?? [];
  const daemons = snapshot?.daemons ?? [];
  const recentConnections = data?.recentConnections ?? [];

  const spanLabel =
    history.length > 1
      ? `Last ${Math.max(1, Math.round((new Date(history[history.length - 1].at).getTime() - new Date(history[0].at).getTime()) / 60_000))} min`
      : history.length === 1
        ? "Live"
        : "";

  const counts = {
    connections: snapshot?.activeConnections ?? data?.activeConnections ?? 0,
    users: data?.users ?? 0,
    groups: data?.groups ?? 0,
    certificates: data?.activeCertificates ?? 0,
  };

  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 3 }}>
        <Typography variant="h5" fontWeight={700}>
          Dashboard
        </Typography>
        <Chip
          size="small"
          color={connected ? "success" : snapshot ? "warning" : "error"}
          label={connected ? "Live" : snapshot ? "Polling" : "Offline"}
        />
      </Box>
      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {(error as Error).message}
        </Alert>
      )}
      <Grid container spacing={3}>
        <StatCard
          title="Active connections"
          value={counts.connections}
          loading={isLoading}
          icon={<LanIcon />}
          color="success"
        />
        <StatCard title="Total users" value={counts.users} loading={isLoading} icon={<PeopleIcon />} color="primary" />
        <StatCard title="Groups" value={counts.groups} loading={isLoading} icon={<GroupsIcon />} color="secondary" />
        <StatCard
          title="Active certificates"
          value={counts.certificates}
          loading={isLoading}
          icon={<VerifiedUserIcon />}
          color="warning"
        />

        <Grid item xs={12} md={8}>
          <Paper sx={{ p: 3 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2, flexWrap: "wrap" }}>
              <Typography variant="h6" fontWeight={600}>
                Network traffic
              </Typography>
              {spanLabel && (
                <Chip size="small" variant="outlined" label={`${spanLabel} · ${history.length} samples`} />
              )}
              <Stack direction="row" spacing={1} sx={{ ml: "auto" }}>
                <Chip size="small" variant="outlined" label={`↓ ${formatRate(snapshot?.bytesOutPerSec ?? 0)}`} />
                <Chip size="small" variant="outlined" label={`↑ ${formatRate(snapshot?.bytesInPerSec ?? 0)}`} />
              </Stack>
            </Box>
            <TrafficChart history={history} />
          </Paper>
        </Grid>

        <Grid item xs={12} md={4}>
          <SystemCard system={snapshot?.system ?? null} loading={!snapshot} />
        </Grid>

        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2, flexWrap: "wrap" }}>
              <Typography variant="h6" fontWeight={600}>
                VPN daemons
              </Typography>
              <Chip
                size="small"
                color={data && data.runningDaemons === data.totalDaemons ? "success" : "warning"}
                label={`Daemons ${data?.runningDaemons ?? 0} / ${data?.totalDaemons ?? 0} running`}
              />
            </Box>
            {daemons.length ? (
              <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
                {daemons.map((d) => (
                  <DaemonChip key={d.index} daemon={d} />
                ))}
              </Stack>
            ) : (
              <Typography variant="body2" color="text.secondary">
                Waiting for live daemon data…
              </Typography>
            )}
          </Paper>
        </Grid>

        <Grid item xs={12}>
          <Paper sx={{ p: 3, overflowX: "auto" }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2 }}>
              <Typography variant="h6" fontWeight={600}>
                Recent connections
              </Typography>
            </Box>
            {isLoading ? (
              <Skeleton height={140} />
            ) : recentConnections.length ? (
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>User</TableCell>
                    <TableCell>Common name</TableCell>
                    <TableCell>VPN IP</TableCell>
                    <TableCell>Remote IP</TableCell>
                    <TableCell>Daemon</TableCell>
                    <TableCell>Download</TableCell>
                    <TableCell>Upload</TableCell>
                    <TableCell>Connected</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {recentConnections.map((c) => (
                    <TableRow key={c.commonName} hover>
                      <TableCell>{c.username ?? "—"}</TableCell>
                      <TableCell>{c.commonName}</TableCell>
                      <TableCell>{c.virtualIp ?? "—"}</TableCell>
                      <TableCell>{c.remoteIp ?? "—"}</TableCell>
                      <TableCell>{c.daemonName ?? "—"}</TableCell>
                      <TableCell>{formatBytes(c.bytesOut ?? 0)}</TableCell>
                      <TableCell>{formatBytes(c.bytesIn ?? 0)}</TableCell>
                      <TableCell>{formatSince(c.connectedAt)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <Typography variant="body2" color="text.secondary">
                No active connections right now.
              </Typography>
            )}
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
}
