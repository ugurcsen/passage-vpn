import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import { api, endpoints, type Daemon } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

interface DaemonForm {
  name: string;
  daemonIndex: string;
  port: string;
  proto: "udp" | "tcp";
  subnet: string;
  subnetMask: string;
  dnsServers: string;
  domain: string;
  extraRoutes: string;
  fullTunnel: boolean;
  clientCertNotRequired: boolean;
  authUserPass: boolean;
  adminHost: string;
  enabled: boolean;
}

const EMPTY_FORM: DaemonForm = {
  name: "",
  daemonIndex: "",
  port: "",
  proto: "udp",
  subnet: "",
  subnetMask: "255.255.255.0",
  dnsServers: "1.1.1.1, 8.8.8.8",
  domain: "",
  extraRoutes: "",
  fullTunnel: true,
  clientCertNotRequired: false,
  authUserPass: true,
  adminHost: "",
  enabled: true,
};

/** Profile types a daemon serves, derived from its flag combination. */
function daemonRole(d: Daemon): string {
  if (d.clientCertNotRequired) return "Generic";
  if (!d.authUserPass) return "Auto-login";
  return "User-locked / Server-locked";
}

function splitList(value: string): string[] {
  return value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

export function DaemonsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<DaemonForm>(EMPTY_FORM);
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);

  const { data: daemons, isLoading } = useQuery<Daemon[]>({
    queryKey: ["admin-daemons"],
    queryFn: () => api<Daemon[]>(endpoints.daemons),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-daemons"] });

  const save = useMutation({
    mutationFn: async () => {
      const payload = {
        name: form.name || null,
        daemonIndex: Number(form.daemonIndex),
        port: Number(form.port),
        proto: form.proto,
        subnet: form.subnet,
        subnetMask: form.subnetMask,
        dnsServers: splitList(form.dnsServers),
        domain: form.domain || null,
        extraRoutes: splitList(form.extraRoutes),
        fullTunnel: form.fullTunnel,
        clientCertNotRequired: form.clientCertNotRequired,
        authUserPass: form.authUserPass,
        adminHost: form.adminHost || null,
        enabled: form.enabled,
      };
      if (editing) {
        return api(endpoints.daemons + `/${editing}`, { method: "PUT", body: JSON.stringify(payload) });
      }
      return api(endpoints.daemons, { method: "POST", body: JSON.stringify(payload) });
    },
    onSuccess: () => {
      toast.success(editing ? "Daemon updated" : "Daemon created");
      setDialogOpen(false);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const toggleEnabled = useMutation({
    mutationFn: (row: Daemon) =>
      api(`${endpoints.daemons}/${row.id}/enabled?enabled=${!row.enabled}`, { method: "POST" }),
    onSuccess: () => {
      toast.success("Daemon updated");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => api(endpoints.daemons + `/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      toast.success("Daemon deleted");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const openCreate = () => {
    setEditing(null);
    setForm({ ...EMPTY_FORM, daemonIndex: String((daemons ?? []).length) });
    setDialogOpen(true);
  };

  const openEdit = (row: Daemon) => {
    setEditing(row.id);
    setForm({
      name: row.name ?? "",
      daemonIndex: String(row.daemonIndex),
      port: String(row.port),
      proto: row.proto,
      subnet: row.subnet,
      subnetMask: row.subnetMask,
      dnsServers: row.dnsServers.join(", "),
      domain: row.domain ?? "",
      extraRoutes: row.extraRoutes.join(", "),
      fullTunnel: row.fullTunnel,
      clientCertNotRequired: row.clientCertNotRequired,
      authUserPass: row.authUserPass,
      adminHost: row.adminHost ?? "",
      enabled: row.enabled,
    });
    setDialogOpen(true);
  };

  const columns: GridColDef[] = [
    {
      field: "name",
      headerName: "Daemon",
      flex: 1.2,
      minWidth: 180,
      renderCell: (params) => {
        const row = params.row as Daemon;
        return <Typography variant="body2">{row.name ?? `Daemon ${row.daemonIndex}`}</Typography>;
      },
    },
    {
      field: "endpoint",
      headerName: "Endpoint",
      width: 130,
      valueGetter: (_, row) => `${(row as Daemon).proto.toUpperCase()}:${(row as Daemon).port}`,
    },
    {
      field: "subnet",
      headerName: "Subnet",
      width: 150,
      valueGetter: (_, row) => `${(row as Daemon).subnet}/${(row as Daemon).subnetMask}`,
    },
    {
      field: "role",
      headerName: "Serves profiles",
      width: 190,
      renderCell: (params) => {
        const row = params.row as Daemon;
        const role = daemonRole(row);
        const color =
          role === "Generic" ? "secondary" : role === "Auto-login" ? "warning" : "default";
        return <Chip label={role} size="small" color={color} variant="outlined" />;
      },
    },
    {
      field: "fullTunnel",
      headerName: "Routing",
      width: 110,
      valueGetter: (_, row) => ((row as Daemon).fullTunnel ? "Full tunnel" : "Split tunnel"),
    },
    {
      field: "enabled",
      headerName: "Enabled",
      width: 100,
      renderCell: (params) => {
        const row = params.row as Daemon;
        return (
          <Switch size="small" checked={row.enabled} onChange={() => toggleEnabled.mutate(row)} />
        );
      },
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 100,
      sortable: false,
      filterable: false,
      renderCell: (params) => {
        const row = params.row as Daemon;
        return (
          <Stack direction="row">
            <Tooltip title="Edit">
              <IconButton size="small" onClick={() => openEdit(row)}>
                <EditIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="Delete">
              <IconButton
                size="small"
                disabled={row.primary}
                onClick={() =>
                  setConfirm({
                    title: "Delete daemon",
                    text: `Delete "${row.name ?? row.daemonIndex}"? Its config file will be removed.`,
                    action: () => remove.mutate(row.id),
                  })
                }
              >
                <DeleteIcon fontSize="small" color="error" />
              </IconButton>
            </Tooltip>
          </Stack>
        );
      },
    },
  ];

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="h5" fontWeight={700}>
          VPN daemons
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
          New daemon
        </Button>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Each daemon is a separate OpenVPN instance with its own port and subnet. Connection profiles
        route to a matching daemon: GENERIC needs a client-cert-not-required daemon, AUTO_LOGIN a
        cert-only daemon (no password), and user-locked/server-locked profiles use a password
        daemon. Each daemon needs a unique port, subnet and management port.
      </Typography>
      <Paper sx={{ height: 520 }}>
        <DataGrid
          rows={daemons ?? []}
          columns={columns}
          loading={isLoading}
          pagination
          pageSizeOptions={[10, 25, 50]}
          initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
        />
      </Paper>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? "Edit daemon" : "New daemon"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Stack direction="row" spacing={2}>
              <TextField
                label="Name"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="e.g. Generic access"
                sx={{ flex: 1 }}
              />
              <TextField
                label="Daemon index"
                value={form.daemonIndex}
                onChange={(e) => setForm({ ...form, daemonIndex: e.target.value })}
                required
                sx={{ width: 140 }}
                helperText="0 is the primary daemon"
              />
            </Stack>
            <Stack direction="row" spacing={2}>
              <TextField
                label="Port"
                value={form.port}
                onChange={(e) => setForm({ ...form, port: e.target.value })}
                required
                sx={{ width: 140 }}
              />
              <TextField
                select
                label="Protocol"
                value={form.proto}
                onChange={(e) => setForm({ ...form, proto: e.target.value as "udp" | "tcp" })}
                sx={{ width: 140 }}
              >
                <MenuItem value="udp">UDP</MenuItem>
                <MenuItem value="tcp">TCP</MenuItem>
              </TextField>
              <TextField
                label="Admin host"
                value={form.adminHost}
                onChange={(e) => setForm({ ...form, adminHost: e.target.value })}
                placeholder="vpn.example.com"
                sx={{ flex: 1 }}
              />
            </Stack>
            <Stack direction="row" spacing={2}>
              <TextField
                label="Subnet"
                value={form.subnet}
                onChange={(e) => setForm({ ...form, subnet: e.target.value })}
                required
                placeholder="10.8.0.0"
                sx={{ flex: 1 }}
              />
              <TextField
                label="Subnet mask"
                value={form.subnetMask}
                onChange={(e) => setForm({ ...form, subnetMask: e.target.value })}
                required
                placeholder="255.255.255.0"
                sx={{ width: 160 }}
              />
            </Stack>
            <TextField
              label="DNS servers (comma separated)"
              value={form.dnsServers}
              onChange={(e) => setForm({ ...form, dnsServers: e.target.value })}
              placeholder="1.1.1.1, 8.8.8.8"
            />
            <Stack direction="row" spacing={2}>
              <TextField
                label="DNS domain"
                value={form.domain}
                onChange={(e) => setForm({ ...form, domain: e.target.value })}
                sx={{ flex: 1 }}
              />
              <TextField
                label="Extra routes (comma separated)"
                value={form.extraRoutes}
                onChange={(e) => setForm({ ...form, extraRoutes: e.target.value })}
                placeholder="192.168.0.0/24"
                sx={{ flex: 1 }}
              />
            </Stack>
            <FormControlLabel
              control={
                <Switch
                  checked={form.fullTunnel}
                  onChange={(e) => setForm({ ...form, fullTunnel: e.target.checked })}
                />
              }
              label="Full tunnel (route all traffic through VPN)"
            />
            <FormControlLabel
              control={
                <Switch
                  checked={form.clientCertNotRequired}
                  onChange={(e) => setForm({ ...form, clientCertNotRequired: e.target.checked })}
                />
              }
              label="Client cert not required (serves GENERIC profiles)"
            />
            <FormControlLabel
              control={
                <Switch
                  checked={form.authUserPass}
                  onChange={(e) => setForm({ ...form, authUserPass: e.target.checked })}
                />
              }
              label="Username/password auth (disable for AUTO_LOGIN cert-only daemon)"
            />
            <FormControlLabel
              control={
                <Switch
                  checked={form.enabled}
                  onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
                />
              }
              label="Enabled"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!form.daemonIndex || !form.port || !form.subnet || !form.subnetMask}
            onClick={() => save.mutate()}
          >
            {editing ? "Save" : "Create"}
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger
        confirmLabel="Delete"
        loading={remove.isPending}
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          confirm?.action();
          setConfirm(null);
        }}
      />
    </Box>
  );
}
