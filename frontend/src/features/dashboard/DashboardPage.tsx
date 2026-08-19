import {
  Alert,
  Box,
  Button,
  Chip,
  Grid,
  Paper,
  Stack,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import AutoAwesomeIcon from "@mui/icons-material/AutoAwesome";
import GroupsIcon from "@mui/icons-material/Groups";
import LanIcon from "@mui/icons-material/Lan";
import PeopleIcon from "@mui/icons-material/People";
import VerifiedUserIcon from "@mui/icons-material/VerifiedUser";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { useToast } from "@/hooks/useToast";
import { api, demoSeed, endpoints, type DashboardStats } from "@/lib/api";
import { useLiveStatus } from "@/hooks/useLiveStatus";
import { StatCard } from "./StatCard";
import { TrafficChart } from "./TrafficChart";
import { SystemCard } from "./SystemCard";
import { DaemonChip } from "./DaemonChip";
import { RecentConnections } from "./RecentConnections";

/** Dashboard: live stat cards, real-time traffic chart, host system card and daemon health. */
export function DashboardPage() {
  const queryClient = useQueryClient();
  const { success, error: toastError } = useToast();
  const [seedOpen, setSeedOpen] = useState(false);
  const { data, isLoading, error } = useQuery<DashboardStats>({
    queryKey: ["admin-dashboard"],
    queryFn: () => api<DashboardStats>(endpoints.dashboard),
    refetchInterval: 15_000,
  });
  const { snapshot, connected } = useLiveStatus();
  const seedMutation = useMutation({
    mutationFn: () => demoSeed(false),
    onSuccess: (result) => {
      setSeedOpen(false);
      success(`Demo data loaded: ${result.users} sample users`);
      void queryClient.invalidateQueries({ queryKey: ["admin-dashboard"] });
    },
    onError: (err) => {
      setSeedOpen(false);
      toastError((err as Error).message);
    },
  });

  const history = snapshot?.history ?? [];
  const daemons = snapshot?.daemons ?? [];

  const spanLabel =
    history.length > 1
      ? `Last ${Math.max(1, Math.round((new Date(history[history.length - 1].at).getTime() - new Date(history[0].at).getTime()) / 60_000))} min`
      : history.length === 1
        ? "Live"
        : "";

  const counts = {
    connections: snapshot?.activeConnections ?? data?.activeConnections ?? 0,
    users: data?.users ?? 0,
    groups: data?.groups ?? 0,
    certificates: data?.activeCertificates ?? 0,
  };

  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 3 }}>
        <Typography variant="h5" fontWeight={700}>
          Dashboard
        </Typography>
        <Chip
          size="small"
          color={connected ? "success" : snapshot ? "warning" : "error"}
          label={connected ? "Live" : snapshot ? "Polling" : "Offline"}
        />
        <Button
          size="small"
          variant="outlined"
          color="info"
          startIcon={<AutoAwesomeIcon />}
          onClick={() => setSeedOpen(true)}
          sx={{ ml: "auto" }}
        >
          Load demo data
        </Button>
      </Box>
      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {(error as Error).message}
        </Alert>
      )}
      <Grid container spacing={3}>
        <StatCard
          title="Active connections"
          value={counts.connections}
          loading={isLoading}
          icon={<LanIcon />}
          color="success"
        />
        <StatCard title="Total users" value={counts.users} loading={isLoading} icon={<PeopleIcon />} color="primary" />
        <StatCard title="Groups" value={counts.groups} loading={isLoading} icon={<GroupsIcon />} color="secondary" />
        <StatCard
          title="Active certificates"
          value={counts.certificates}
          loading={isLoading}
          icon={<VerifiedUserIcon />}
          color="warning"
        />

        <Grid item xs={12} md={8}>
          <TrafficChart
            history={history}
            spanLabel={spanLabel}
            bytesInPerSec={snapshot?.bytesInPerSec}
            bytesOutPerSec={snapshot?.bytesOutPerSec}
          />
        </Grid>

        <Grid item xs={12} md={4}>
          <SystemCard system={snapshot?.system ?? null} loading={!snapshot} />
        </Grid>

        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2, flexWrap: "wrap" }}>
              <Typography variant="h6" fontWeight={600}>
                VPN daemons
              </Typography>
              <Chip
                size="small"
                color={data && data.runningDaemons === data.totalDaemons ? "success" : "warning"}
                label={`Daemons ${data?.runningDaemons ?? 0} / ${data?.totalDaemons ?? 0} running`}
              />
            </Box>
            {daemons.length ? (
              <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
                {daemons.map((d) => (
                  <DaemonChip key={d.index} daemon={d} />
                ))}
              </Stack>
            ) : (
              <Typography variant="body2" color="text.secondary">
                Waiting for live daemon data…
              </Typography>
            )}
          </Paper>
        </Grid>

        <RecentConnections connections={data?.recentConnections ?? []} loading={isLoading} />
      </Grid>
      <ConfirmDialog
        open={seedOpen}
        title="Load demo data"
        message="This creates sample users (alice, bob, carol, dave), groups with static IP pools, access rules, DNS overrides and connection history for trying out the panel. Real client certificates are not issued."
        confirmLabel="Load"
        danger={false}
        loading={seedMutation.isPending}
        onConfirm={() => seedMutation.mutate()}
        onCancel={() => setSeedOpen(false)}
      />
    </Box>
  );
}
