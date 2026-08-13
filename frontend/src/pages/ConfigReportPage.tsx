import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Grid,
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
import { api, endpoints, type ConfigReport } from "@/lib/api";

/** Read-only snapshot of the running configuration, for support and auditing. */
export function ConfigReportPage() {
  const { data, isLoading, error } = useQuery<ConfigReport>({
    queryKey: ["config-report"],
    queryFn: () => api<ConfigReport>(endpoints.configReport),
  });

  if (error) {
    return (
      <Box>
        <Typography variant="h5" fontWeight={700} sx={{ mb: 3 }}>
          Config Report
        </Typography>
        <Alert severity="error">{(error as Error).message}</Alert>
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 1 }}>
        Config Report
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Snapshot of the running configuration; useful when sharing setup details for support.
      </Typography>

      {isLoading ? (
        <Skeleton height={240} />
      ) : data ? (
        <Stack spacing={3}>
          <Paper sx={{ p: 3 }}>
            <Grid container spacing={2}>
              <InfoCell label="Brand" value={data.brand} />
              <InfoCell label="Version" value={data.version} />
              <InfoCell label="Generated" value={data.generatedAt} />
              <InfoCell label="Database" value={data.dbType} />
              <InfoCell label="Users" value={String(data.users)} />
              <InfoCell label="Groups" value={String(data.groups)} />
            </Grid>
          </Paper>

          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
              Data directories
            </Typography>
            <Grid container spacing={2}>
              <InfoCell label="PKI" value={data.dataDirs.pki} />
              <InfoCell label="CCD" value={data.dataDirs.ccd} />
              <InfoCell label="Configs" value={data.dataDirs.config} />
              <InfoCell label="Logs" value={data.dataDirs.logs} />
            </Grid>
          </Paper>

          <Paper sx={{ p: 3, overflowX: "auto" }}>
            <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
              PKI inventory
            </Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Total</TableCell>
                  <TableCell>Valid</TableCell>
                  <TableCell>Revoked</TableCell>
                  <TableCell>Expired</TableCell>
                  <TableCell>Expiring soon</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                <TableRow>
                  <TableCell>{data.pki.total}</TableCell>
                  <TableCell>{data.pki.valid}</TableCell>
                  <TableCell>{data.pki.revoked}</TableCell>
                  <TableCell>{data.pki.expired}</TableCell>
                  <TableCell>{data.pki.expiringSoon}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </Paper>

          <Paper sx={{ p: 3, overflowX: "auto" }}>
            <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
              Daemons
            </Typography>
            {data.daemons.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                No daemons configured.
              </Typography>
            ) : (
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Index</TableCell>
                    <TableCell>Name</TableCell>
                    <TableCell>Port</TableCell>
                    <TableCell>Protocol</TableCell>
                    <TableCell>Enabled</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.daemons.map((d) => (
                    <TableRow key={d.index}>
                      <TableCell>{d.index}</TableCell>
                      <TableCell>{d.name ?? "—"}</TableCell>
                      <TableCell>{d.port}</TableCell>
                      <TableCell>{d.proto.toUpperCase()}</TableCell>
                      <TableCell>{d.enabled ? "Yes" : "No"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Paper>

          <Paper sx={{ p: 3, overflowX: "auto" }}>
            <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
              Server settings
            </Typography>
            <Typography variant="body2" component="pre" sx={{ m: 0, fontFamily: "monospace", fontSize: "0.8rem", whiteSpace: "pre-wrap" }}>
              {JSON.stringify(data.serverSettings, null, 2)}
            </Typography>
          </Paper>
        </Stack>
      ) : null}
    </Box>
  );
}

function InfoCell({ label, value }: { label: string; value: string }) {
  return (
    <Grid item xs={12} sm={6} md={4} lg={2}>
      <Typography variant="caption" color="text.secondary" display="block">
        {label}
      </Typography>
      <Typography variant="body2" sx={{ overflowWrap: "anywhere" }}>
        {value}
      </Typography>
    </Grid>
  );
}
