import { Box, Grid, Paper, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import type { DashboardStats } from "@/lib/api";
import { formatBytes, formatSince } from "./helpers";

type RecentConnection = DashboardStats["recentConnections"][number];

export function RecentConnections({
  connections,
  loading,
}: {
  connections: RecentConnection[];
  loading: boolean;
}) {
  return (
    <Grid item xs={12}>
      <Paper sx={{ p: 3, overflowX: "auto" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2 }}>
          <Typography variant="h6" fontWeight={600}>
            Recent connections
          </Typography>
        </Box>
        {loading ? (
          <Skeleton height={140} />
        ) : connections.length ? (
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
              {connections.map((c) => (
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
  );
}
