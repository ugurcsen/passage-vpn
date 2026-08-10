import { Alert, Box, Chip, Grid, Paper, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { api, endpoints, type DashboardStats } from "@/lib/api";

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

/** Dashboard: live stat cards, daemon health and recent connections. */
export function DashboardPage() {
  const { data, isLoading, error } = useQuery<DashboardStats>({
    queryKey: ["admin-dashboard"],
    queryFn: () => api<DashboardStats>(endpoints.dashboard),
    refetchInterval: 15_000,
  });

  const counts = {
    connections: data?.activeConnections ?? 0,
    users: data?.users ?? 0,
    groups: data?.groups ?? 0,
    certificates: data?.activeCertificates ?? 0,
  };

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 3 }}>
        Dashboard
      </Typography>
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

        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2 }}>
              <Typography variant="h6" fontWeight={600}>
                Recent connections
              </Typography>
              <Chip
                size="small"
                color={data && data.runningDaemons === data.totalDaemons ? "success" : "warning"}
                label={`Daemons ${data?.runningDaemons ?? 0} / ${data?.totalDaemons ?? 0} running`}
              />
            </Box>
            {isLoading ? (
              <Skeleton height={140} />
            ) : data?.recentConnections.length ? (
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>User</TableCell>
                    <TableCell>Common name</TableCell>
                    <TableCell>VPN IP</TableCell>
                    <TableCell>Remote IP</TableCell>
                    <TableCell>Daemon</TableCell>
                    <TableCell>Connected</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.recentConnections.map((c) => (
                    <TableRow key={c.commonName}>
                      <TableCell>{c.username ?? "—"}</TableCell>
                      <TableCell>{c.commonName}</TableCell>
                      <TableCell>{c.virtualIp ?? "—"}</TableCell>
                      <TableCell>{c.remoteIp ?? "—"}</TableCell>
                      <TableCell>{c.daemonName ?? "—"}</TableCell>
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
