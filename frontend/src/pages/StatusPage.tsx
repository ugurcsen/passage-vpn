import {
  Alert,
  Box,
  Button,
  Chip,
  Paper,
  Skeleton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import RefreshIcon from "@mui/icons-material/Refresh";
import {
  api,
  endpoints,
  type OpenVpnNode,
  type ServerStatus,
} from "@/lib/api";
import { useLiveStatus } from "@/hooks/useLiveStatus";

function formatUptime(totalSeconds: number) {
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m ${totalSeconds % 60}s`;
}

function formatBytes(bytes: number | null | undefined) {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes <= 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}

function formatRate(bytesPerSec: number | null | undefined) {
  if (bytesPerSec === null || bytesPerSec === undefined || !Number.isFinite(bytesPerSec) || bytesPerSec <= 0) {
    return "—";
  }
  if (bytesPerSec < 1024) return `${bytesPerSec.toFixed(0)} B/s`;
  if (bytesPerSec < 1024 ** 2) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSec / 1024 ** 2).toFixed(2)} MB/s`;
}

function StatusChip({ ok, label }: { ok: boolean; label: string }) {
  return <Chip size="small" color={ok ? "success" : "error"} label={label} />;
}

/** Live status: daemon health (with DCO), active connections with traffic. */
export function StatusPage() {
  const statusQuery = useQuery<ServerStatus>({
    queryKey: ["admin-status"],
    queryFn: () => api<ServerStatus>(endpoints.status),
    refetchInterval: 30_000,
  });

  const { snapshot, error: liveError } = useLiveStatus();

  const { data, isLoading, error, refetch, isFetching } = statusQuery;
  const daemons = snapshot?.daemons ?? data?.daemons ?? [];
  const connections = snapshot?.connections ?? [];

  const { data: nodes } = useQuery<OpenVpnNode[]>({
    queryKey: ["admin-nodes"],
    queryFn: () => api<OpenVpnNode[]>(endpoints.nodes),
  });

  const nodeName = (nodeId: string | null | undefined): string =>
    nodeId == null ? "Local" : (nodes?.find((n) => n.id === nodeId)?.name ?? nodeId);

  const refreshAll = () => {
    void refetch();
  };

  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 3 }}>
        <Typography variant="h5" fontWeight={700}>
          Live status
        </Typography>
        <Button startIcon={<RefreshIcon />} onClick={refreshAll} disabled={isFetching}>
          Refresh
        </Button>
      </Box>
      {(error || liveError) && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {((error ?? liveError) as Error).message}
        </Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ mb: 3, flexWrap: "wrap", gap: 1 }}>
        <Chip label={data?.brand ?? "—"} variant="outlined" />
        <Chip label={`v${data?.version ?? "—"}`} variant="outlined" />
        {data && <Chip label={`Up ${formatUptime(data.uptimeSeconds)}`} variant="outlined" />}
        <Chip label={`${snapshot?.activeConnections ?? data?.activeConnections ?? 0} active connections`} variant="outlined" />
        <Chip
          size="small"
          color={snapshot ? "success" : "default"}
          label={`↓ ${formatRate(snapshot?.bytesOutPerSec)}  ↑ ${formatRate(snapshot?.bytesInPerSec)}`}
          variant="outlined"
        />
      </Stack>

      <Paper sx={{ p: 3, mb: 3, overflowX: "auto" }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
          VPN daemons
        </Typography>
        {isLoading && !daemons.length ? (
          <Skeleton height={140} />
        ) : (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Index</TableCell>
                <TableCell>Name</TableCell>
                <TableCell>Endpoint</TableCell>
                <TableCell>Node</TableCell>
                <TableCell>Data channel</TableCell>
                <TableCell>Enabled</TableCell>
                <TableCell>Config</TableCell>
                <TableCell>Management</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {daemons.map((d) => (
                <TableRow key={d.index}>
                  <TableCell>{d.index}</TableCell>
                  <TableCell>{d.name ?? "—"}</TableCell>
                  <TableCell>
                    {d.proto.toUpperCase()}:{d.port}
                  </TableCell>
                  <TableCell>{nodeName(d.nodeId)}</TableCell>
                  <TableCell>
                    <StatusChip
                      ok={d.dco === true}
                      label={d.dco === true ? "DCO" : d.dco === false ? "Userspace" : "—"}
                    />
                  </TableCell>
                  <TableCell>
                    <StatusChip ok={d.enabled} label={d.enabled ? "Enabled" : "Disabled"} />
                  </TableCell>
                  <TableCell>
                    <StatusChip ok={d.configPresent} label={d.configPresent ? "Present" : "Missing"} />
                  </TableCell>
                  <TableCell>
                    <StatusChip ok={d.mgmtReachable} label={d.mgmtReachable ? "Reachable" : "Down"} />
                  </TableCell>
                </TableRow>
              ))}
              {daemons.length === 0 && (
                <TableRow>
                  <TableCell colSpan={8} align="center" sx={{ color: "text.secondary" }}>
                    No daemons configured yet.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        )}
      </Paper>

      <Paper sx={{ p: 3, mb: 3, overflowX: "auto" }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
          Active connections
        </Typography>
        {connections.length ? (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>User</TableCell>
                <TableCell>Common name</TableCell>
                <TableCell>VPN IP</TableCell>
                <TableCell>Remote IP</TableCell>
                <TableCell>Daemon</TableCell>
                <TableCell>Node</TableCell>
                <TableCell>Download</TableCell>
                <TableCell>Upload</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {connections.map((c) => (
                <TableRow key={c.commonName}>
                  <TableCell>{c.username ?? "—"}</TableCell>
                  <TableCell>{c.commonName}</TableCell>
                  <TableCell>{c.virtualIp ?? "—"}</TableCell>
                  <TableCell>{c.remoteIp ?? "—"}</TableCell>
                  <TableCell>{c.daemonName ?? "—"}</TableCell>
                  <TableCell>{nodeName(c.nodeId)}</TableCell>
                  <TableCell>{formatBytes(c.bytesOut)}</TableCell>
                  <TableCell>{formatBytes(c.bytesIn)}</TableCell>
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
    </Box>
  );
}
