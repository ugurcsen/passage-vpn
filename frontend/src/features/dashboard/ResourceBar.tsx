import { Box, LinearProgress, Stack, Typography } from "@mui/material";
import type { ReactNode } from "react";

export function ResourceBar({
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
