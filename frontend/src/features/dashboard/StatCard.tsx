import { Avatar, Box, Grid, Paper, Skeleton, Typography } from "@mui/material";
import type { ReactNode } from "react";

export function StatCard({
  title,
  value,
  loading,
  icon,
  color,
}: {
  title: string;
  value: number;
  loading: boolean;
  icon: ReactNode;
  color: "primary" | "secondary" | "success" | "warning" | "error" | "info";
}) {
  return (
    <Grid item xs={12} sm={6} md={3}>
      <Paper sx={{ p: 2.5, display: "flex", alignItems: "center", gap: 2, height: "100%" }}>
        <Avatar sx={{ bgcolor: `${color}.main`, width: 46, height: 46 }}>
          <Box component="span" sx={{ display: "flex", color: "#fff" }}>
            {icon}
          </Box>
        </Avatar>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" color="text.secondary" noWrap>
            {title}
          </Typography>
          {loading ? (
            <Skeleton variant="text" width="50%" sx={{ fontSize: "2rem" }} />
          ) : (
            <Typography variant="h4" fontWeight={700}>
              {value}
            </Typography>
          )}
        </Box>
      </Paper>
    </Grid>
  );
}
