import { Alert, Box, Button, Chip, Paper, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import RefreshIcon from "@mui/icons-material/Refresh";
import { api, endpoints, type ServerStatus, type VpnConnection } from "@/lib/api";

function formatUptime(totalSeconds: number) {
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m ${totalSeconds % 60}s`;
}

function StatusChip({ ok, label }: { ok: boolean; label: string }) {
  return <Chip size="small" color={ok ? "success" : "error"} label={label} />;
}

/** Live status: daemon health table and active connections. */
export function StatusPage() {
  const statusQuery = useQuery<ServerStatus>({
    queryKey: ["admin-status"],
    queryFn: () => api<ServerStatus>(endpoints.status),
    refetchInterval: 10_000,
  });

  const connectionsQuery = useQuery<VpnConnection[]>({
    queryKey: ["admin-connections"],
    queryFn: () => api<VpnConnection[]>(endpoints.connections),
    refetchInterval: 10_000,
  });

  const { data, isLoading, error, refetch, isFetching } = statusQuery;

  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 3 }}>
        <Typography variant="h5" fontWeight={700}>
          Live status
        </Typography>
        <Button startIcon={<RefreshIcon />} onClick={() => refetch()} disabled={isFetching}>
          Refresh
        </Button>
      </Box>
      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {(error as Error).message}
        </Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ mb: 3, flexWrap: "wrap", gap: 1 }}>
        <Chip label={data?.brand ?? "—"} variant="outlined" />
        <Chip label={`v${data?.version ?? "—"}`} variant="outlined" />
        {data && <Chip label={`Up ${formatUptime(data.uptimeSeconds)}`} variant="outlined" />}
        <Chip label={`${data?.activeConnections ?? 0} active connections`} variant="outlined" />
      </Stack>

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
          VPN daemons
        </Typography>
        {isLoading ? (
          <Skeleton height={140} />
        ) : (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Index</TableCell>
                <TableCell>Name</TableCell>
                <TableCell>Endpoint</TableCell>
                <TableCell>Enabled</TableCell>
                <TableCell>Config</TableCell>
                <TableCell>Management</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data?.daemons.map((d) => (
                <TableRow key={d.index}>
                  <TableCell>{d.index}</TableCell>
                  <TableCell>{d.name ?? "—"}</TableCell>
                  <TableCell>
                    {d.proto.toUpperCase()} :{d.port}
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
              {data?.daemons.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ color: "text.secondary" }}>
                    No daemons configured yet.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        )}
      </Paper>

      <Paper sx={{ p: 3 }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
          Active connections
        </Typography>
        {connectionsQuery.isLoading ? (
          <Skeleton height={120} />
        ) : connectionsQuery.data?.length ? (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>User</TableCell>
                <TableCell>Common name</TableCell>
                <TableCell>VPN IP</TableCell>
                <TableCell>Remote IP</TableCell>
                <TableCell>Daemon</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {connectionsQuery.data.map((c) => (
                <TableRow key={c.commonName}>
                  <TableCell>{c.username ?? "—"}</TableCell>
                  <TableCell>{c.commonName}</TableCell>
                  <TableCell>{c.virtualIp ?? "—"}</TableCell>
                  <TableCell>{c.remoteIp ?? "—"}</TableCell>
                  <TableCell>{c.daemonName ?? "—"}</TableCell>
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
