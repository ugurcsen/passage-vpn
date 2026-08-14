import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  Chip,
  List,
  ListItem,
  ListItemIcon,
  ListItemText,
  Paper,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import ErrorIcon from "@mui/icons-material/Error";
import RefreshIcon from "@mui/icons-material/Refresh";
import RestartAltIcon from "@mui/icons-material/RestartAlt";
import SyncIcon from "@mui/icons-material/Sync";
import WarningIcon from "@mui/icons-material/Warning";
import {
  reloadDaemons,
  restartBackend,
  runPreflight,
  type PreflightCheck,
  type PreflightResult,
} from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

function statusIcon(status: PreflightCheck["status"]) {
  switch (status) {
    case "PASS":
      return <CheckCircleIcon color="success" />;
    case "WARN":
      return <WarningIcon color="warning" />;
    default:
      return <ErrorIcon color="error" />;
  }
}

function statusColor(status: PreflightCheck["status"]) {
  switch (status) {
    case "PASS":
      return "success";
    case "WARN":
      return "warning";
    default:
      return "error";
  }
}

/** Maintenance page: preflight safety checks and the Danger Zone (restart / reload). */
export function MaintenancePage() {
  const toast = useToast();
  const [preflight, setPreflight] = useState<PreflightResult | null>(null);
  const [confirm, setConfirm] = useState<"restart" | "reload" | null>(null);

  const preflightRun = useMutation({
    mutationFn: () => runPreflight(),
    onSuccess: (result) => setPreflight(result),
    onError: (err) => toast.error(err instanceof Error ? err.message : "Preflight failed"),
  });

  const restart = useMutation({
    mutationFn: () => restartBackend(),
    onSuccess: (result) => {
      toast.success(
        `${result.message} The browser will disconnect and reconnect once the backend is back up.`,
      );
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Restart failed"),
  });

  const reload = useMutation({
    mutationFn: () => reloadDaemons(),
    onSuccess: (result) => {
      if (result.failed.length > 0) {
        toast.error(
          `Reloaded ${result.signaled} of ${result.total} daemon(s); unreachable: ${result.failed.join(", ")}`,
        );
      } else {
        toast.success(`Reloaded ${result.signaled} of ${result.total} daemon(s)`);
      }
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Reload failed"),
  });

  const failedChecks = preflight?.checks.filter((c) => c.status === "FAIL") ?? [];
  const pending =
    preflightRun.isPending || restart.isPending || reload.isPending;

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 1 }}>
        Maintenance
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Run safety checks before applying maintenance, then restart the backend or reload the
        OpenVPN daemons. Restart and reload are refused automatically while a check fails.
      </Typography>

      <Paper sx={{ p: 3, mb: 3 }}>
        <Stack
          direction={{ xs: "column", sm: "row" }}
          justifyContent="space-between"
          alignItems={{ xs: "flex-start", sm: "center" }}
          sx={{ mb: 2 }}
        >
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="h6" fontWeight={600}>
              Preflight checks
            </Typography>
            {preflight && (
              <Chip
                label={preflight.passed ? "Ready" : "Issues found"}
                color={preflight.passed ? "success" : "error"}
                size="small"
              />
            )}
          </Stack>
          <Button
            variant="outlined"
            startIcon={<RefreshIcon />}
            disabled={preflightRun.isPending}
            onClick={() => preflightRun.mutate()}
          >
            Run preflight
          </Button>
        </Stack>

        {preflightRun.isPending ? (
          <Typography variant="body2" color="text.secondary">
            Running database, settings, config and PKI checks...
          </Typography>
        ) : !preflight ? (
          <Typography variant="body2" color="text.secondary">
            No checks run yet. Click “Run preflight” to validate the installation before restarting
            or reloading.
          </Typography>
        ) : (
          <List dense disablePadding>
            {preflight.checks.map((check) => (
              <ListItem key={check.name} sx={{ px: 0 }}>
                <ListItemIcon sx={{ minWidth: 36 }}>
                  <Tooltip title={check.status}>
                    <span>{statusIcon(check.status)}</span>
                  </Tooltip>
                </ListItemIcon>
                <ListItemText
                  primary={check.name}
                  secondary={check.detail}
                  primaryTypographyProps={{ fontWeight: 600 }}
                />
                <Chip label={check.status} color={statusColor(check.status)} size="small" />
              </ListItem>
            ))}
          </List>
        )}

        {failedChecks.length > 0 && (
          <Alert severity="error" sx={{ mt: 2 }}>
            Restart and reload are blocked until the failing check(s) are resolved.
          </Alert>
        )}
      </Paper>

      <Paper sx={{ p: 3, border: "1px solid", borderColor: "divider" }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 1 }}>
          Danger Zone
        </Typography>
        <Stack spacing={2}>
          <Stack
            direction={{ xs: "column", sm: "row" }}
            alignItems={{ xs: "flex-start", sm: "center" }}
            sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 2, gap: 1.5 }}
          >
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="subtitle1" fontWeight={600}>
                Restart backend
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Gracefully restarts the backend process. Under Docker the container comes back
                automatically; on bare metal a supervisor must restart it.
              </Typography>
            </Box>
            <Button
              variant="contained"
              color="error"
              startIcon={<RestartAltIcon />}
              disabled={pending}
              sx={{ flexShrink: 0 }}
              onClick={() => setConfirm("restart")}
            >
              Restart backend
            </Button>
          </Stack>

          <Stack
            direction={{ xs: "column", sm: "row" }}
            alignItems={{ xs: "flex-start", sm: "center" }}
            sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 2, gap: 1.5 }}
          >
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="subtitle1" fontWeight={600}>
                Reload OpenVPN daemons
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Sends SIGHUP to every enabled daemon to re-read its config. Useful after editing
                server settings without a full restart.
              </Typography>
            </Box>
            <Button
              variant="contained"
              color="error"
              startIcon={<SyncIcon />}
              disabled={pending}
              sx={{ flexShrink: 0 }}
              onClick={() => setConfirm("reload")}
            >
              Reload daemons
            </Button>
          </Stack>
        </Stack>
      </Paper>

      <ConfirmDialog
        open={confirm === "restart"}
        title="Restart backend"
        message="The backend will shut down and restart. Active management connections and the UI will briefly drop. Continue?"
        confirmLabel="Restart"
        danger
        loading={restart.isPending}
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          setConfirm(null);
          restart.mutate();
        }}
      />

      <ConfirmDialog
        open={confirm === "reload"}
        title="Reload OpenVPN daemons"
        message="Every enabled daemon will re-read its configuration via SIGHUP. Existing client connections may briefly drop. Continue?"
        confirmLabel="Reload"
        danger
        loading={reload.isPending}
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          setConfirm(null);
          reload.mutate();
        }}
      />
    </Box>
  );
}
