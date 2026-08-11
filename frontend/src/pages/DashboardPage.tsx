import {
  Alert,
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
import { api, endpoints, type DashboardStats, type DaemonHealth, type SystemInfo } from "@/lib/api";
import { useLiveStatus } from "@/hooks/useLiveStatus";

function StatCard({ title, value, loading }: { title: string; value: number; loading: boolean }) {
  return (
    <Grid item xs={12} sm={6} md={3}>
      <Paper sx={{ p: 3 }}>
        <Typography variant="body2" color="text.secondary">
          {title}
        </Typography>
        {loading ? (
          <Skeleton variant="text" width="50%" sx={{ fontSize: "2.25rem" }} />
        ) : (
          <Typography variant="h4" fontWeight={700}>
            {value}
          </Typography>
        )}
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

function formatRate(bytesPerSec: number) {
  if (!Number.isFinite(bytesPerSec) || bytesPerSec <= 0) return "—";
  if (bytesPerSec < 1024) return `${bytesPerSec.toFixed(0)} B/s`;
  if (bytesPerSec < 1024 ** 2) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSec / 1024 ** 2).toFixed(2)} MB/s`;
}

function TrafficChart({ history }: { history: { at: string; bytesInPerSec: number; bytesOutPerSec: number }[] }) {
  if (!history.length) {
    return (
      <Typography variant="body2" color="text.secondary">
        Collecting traffic data… (the chart appears after a few samples)
      </Typography>
    );
  }
  return (
    <Box data-testid="traffic-chart" sx={{ width: "100%" }}>
      <LineChart
        height={260}
        xAxis={[
          {
            data: history.map((p) => new Date(p.at)),
            scaleType: "time",
            valueFormatter: (value: number | Date) => new Date(value).toLocaleTimeString(),
          },
        ]}
        series={[
          { data: history.map((p) => p.bytesOutPerSec), label: "Download", color: "#66bb6a" },
          { data: history.map((p) => p.bytesInPerSec), label: "Upload", color: "#42a5f5" },
        ]}
      />
    </Box>
  );
}

function ResourceBar({ label, percent, detail }: { label: string; percent: number; detail: string }) {
  return (
    <Box sx={{ mb: 1.5 }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", mb: 0.5 }}>
        <Typography variant="body2">{label}</Typography>
        <Typography variant="body2" color="text.secondary">
          {detail}
        </Typography>
      </Box>
      <LinearProgress variant="determinate" value={Math.min(100, Math.max(0, percent))} />
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
      <ResourceBar label="CPU" percent={system.cpuLoadPercent} detail={`${Math.round(system.cpuLoadPercent)}%`} />
      <ResourceBar
        label="Memory"
        percent={ramPct}
        detail={`${formatBytes(ramUsed)} / ${formatBytes(system.totalMemory)}`}
      />
      <ResourceBar
        label="Disk"
        percent={diskPct}
        detail={`${formatBytes(diskUsed)} / ${formatBytes(system.diskTotal)}`}
      />
      <Typography variant="caption" color="text.secondary">
        {system.availableProcessors} logical CPUs
      </Typography>
    </Paper>
  );
}

function DaemonChip({ daemon }: { daemon: DaemonHealth }) {
  const ok = daemon.mgmtReachable && daemon.enabled && daemon.configPresent;
  const label = `#${daemon.index} ${daemon.name ?? "unnamed"} (${daemon.proto.toUpperCase()} :${daemon.port})`;
  return (
    <Tooltip title={ok ? "Management reachable" : "Management unreachable / disabled"}>
      <Chip
        size="small"
        color={ok ? "success" : "error"}
        variant={ok ? "filled" : "outlined"}
        label={
          <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
            {label}
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
        <StatCard title="Active connections" value={counts.connections} loading={isLoading} />
        <StatCard title="Total users" value={counts.users} loading={isLoading} />
        <StatCard title="Groups" value={counts.groups} loading={isLoading} />
        <StatCard title="Active certificates" value={counts.certificates} loading={isLoading} />

        <Grid item xs={12} md={8}>
          <Paper sx={{ p: 3 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2, flexWrap: "wrap" }}>
              <Typography variant="h6" fontWeight={600}>
                Traffic (last 15 min)
              </Typography>
              <Stack direction="row" spacing={1}>
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
          <Paper sx={{ p: 3 }}>
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
                    <TableRow key={c.commonName}>
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
