import { Box, Chip, Paper, Stack, Typography } from "@mui/material";
import { LineChart } from "@mui/x-charts/LineChart";
import { formatRate, formatRateLabel, formatTime } from "./helpers";

export function TrafficChart({
  history,
  spanLabel,
  bytesInPerSec,
  bytesOutPerSec,
}: {
  history: { at: string; bytesInPerSec: number; bytesOutPerSec: number }[];
  spanLabel: string;
  bytesInPerSec?: number | null;
  bytesOutPerSec?: number | null;
}) {
  return (
    <Paper sx={{ p: 3 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2, flexWrap: "wrap" }}>
        <Typography variant="h6" fontWeight={600}>
          Network traffic
        </Typography>
        {spanLabel && (
          <Chip size="small" variant="outlined" label={`${spanLabel} · ${history.length} samples`} />
        )}
        <Stack direction="row" spacing={1} sx={{ ml: "auto" }}>
          <Chip size="small" variant="outlined" label={`↓ ${formatRate(bytesOutPerSec ?? 0)}`} />
          <Chip size="small" variant="outlined" label={`↑ ${formatRate(bytesInPerSec ?? 0)}`} />
        </Stack>
      </Box>
      {!history.length ? (
        <Typography variant="body2" color="text.secondary">
          Collecting traffic data… (the chart appears after a few samples)
        </Typography>
      ) : (
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
                valueFormatter: (value: number | null) => (value == null ? "—" : formatRateLabel(value)),
              },
              {
                data: history.map((p) => p.bytesInPerSec),
                label: "Upload",
                color: "#42a5f5",
                area: true,
                baseline: "min",
                showMark: false,
                valueFormatter: (value: number | null) => (value == null ? "—" : formatRateLabel(value)),
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
      )}
    </Paper>
  );
}
