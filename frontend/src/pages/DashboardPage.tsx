import { Alert, Box, Grid, Paper, Skeleton, Typography } from "@mui/material";

const STAT_CARDS = [
  { title: "Active connections", value: "—" },
  { title: "Total users", value: "—" },
  { title: "Groups", value: "—" },
  { title: "Active certificates", value: "—" },
];

/** Dashboard: real-time stats + traffic charts (populated in Phase 4). */
export function DashboardPage() {
  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 3 }}>
        Dashboard
      </Typography>
      <Alert severity="info" sx={{ mb: 3 }}>
        Live data will appear here after the panel is configured and the VPN daemon is running.
      </Alert>
      <Grid container spacing={3}>
        {STAT_CARDS.map((card) => (
          <Grid item xs={12} sm={6} md={3} key={card.title}>
            <Paper sx={{ p: 3 }}>
              <Typography variant="body2" color="text.secondary">
                {card.title}
              </Typography>
              <Skeleton variant="text" width="60%" sx={{ fontSize: "2.25rem" }} />
            </Paper>
          </Grid>
        ))}
        <Grid item xs={12} md={8}>
          <Paper sx={{ p: 3, minHeight: 320 }}>Traffic chart (Phase 4)</Paper>
        </Grid>
        <Grid item xs={12} md={4}>
          <Paper sx={{ p: 3, minHeight: 320 }}>Recent connections (Phase 4)</Paper>
        </Grid>
      </Grid>
    </Box>
  );
}
