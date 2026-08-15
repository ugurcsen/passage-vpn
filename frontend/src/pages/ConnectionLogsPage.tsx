import { Alert, Box, Button, Paper, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import RefreshIcon from "@mui/icons-material/Refresh";
import { api, endpoints, type ConnectionLog } from "@/lib/api";

function formatDateTime(iso: string) {
  return new Date(iso).toLocaleString();
}

function formatDuration(totalSeconds: number) {
  if (!Number.isFinite(totalSeconds)) return "—";
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  if (hours > 0) return `${hours}h ${minutes % 60}m`;
  if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
  return `${seconds}s`;
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}

/** Connection history for ADMIN and GROUP_ADMIN (group admins only see their own users). */
export function ConnectionLogsPage() {
  const { data, isLoading, error, refetch, isFetching } = useQuery<ConnectionLog[]>({
    queryKey: ["admin-connection-logs"],
    queryFn: () => api<ConnectionLog[]>(endpoints.connectionLogs),
    refetchInterval: 15_000,
  });

  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 2 }}>
        <Typography variant="h5" fontWeight={700}>
          Connection logs
        </Typography>
        <Button startIcon={<RefreshIcon />} onClick={() => void refetch()} disabled={isFetching}>
          Refresh
        </Button>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Persisted VPN sessions, newest first. Group admins only see sessions from users within
        their managed groups.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {(error as Error).message}
        </Alert>
      )}
      <Paper sx={{ p: 3, overflowX: "auto" }}>
        {isLoading ? (
          <Skeleton height={140} />
        ) : data?.length ? (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>User</TableCell>
                <TableCell>Common name</TableCell>
                <TableCell>VPN IP</TableCell>
                <TableCell>Daemon</TableCell>
                <TableCell>Connected</TableCell>
                <TableCell>Disconnected</TableCell>
                <TableCell>Duration</TableCell>
                <TableCell>Download</TableCell>
                <TableCell>Upload</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.map((log) => (
                <TableRow key={`${log.commonName}-${log.connectedAt}`}>
                  <TableCell>{log.username || "—"}</TableCell>
                  <TableCell>{log.commonName}</TableCell>
                  <TableCell>{log.virtualIp ?? "—"}</TableCell>
                  <TableCell>{log.daemonName?.trim() || "—"}</TableCell>
                  <TableCell>{formatDateTime(log.connectedAt)}</TableCell>
                  <TableCell>{log.disconnectedAt ? formatDateTime(log.disconnectedAt) : "Active"}</TableCell>
                  <TableCell>{formatDuration(log.durationSeconds)}</TableCell>
                  <TableCell>{formatBytes(log.bytesIn)}</TableCell>
                  <TableCell>{formatBytes(log.bytesOut)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : (
          <Typography variant="body2" color="text.secondary">
            No recorded sessions yet.
          </Typography>
        )}
      </Paper>
    </Box>
  );
}
