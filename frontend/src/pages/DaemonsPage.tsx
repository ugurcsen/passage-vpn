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
import { api, endpoints, type Daemon, type OpenVpnNode } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

interface DaemonForm {
  name: string;
  daemonIndex: string;
  port: string;
  proto: "udp" | "tcp" | "udp6" | "tcp6";
  subnet: string;
  subnetMask: string;
  dnsServers: string;
  domain: string;
  extraRoutes: string;
  fullTunnel: boolean;
  clientCertNotRequired: boolean;
  authUserPass: boolean;
  adminHost: string;
  nodeId: string;
  ipv6Enabled: boolean;
  ipv6Subnet: string;
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
  nodeId: "",
  ipv6Enabled: false,
  ipv6Subnet: "fd00:1::/64",
  enabled: true,
};

/** Profile types a daemon serves, derived from its flag combination. */
function daemonRole(d: Daemon): string {
  if (d.clientCertNotRequired) return "Generic";
  if (!d.authUserPass) return "Auto-login";
  return "User-locked / Server-locked";
}

function dcoLabel(d: Daemon): string {
  if (d.dco === true) return "DCO";
  if (d.dco === false) return "Userspace";
  return "—";
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

  const { data: nodes } = useQuery<OpenVpnNode[]>({
    queryKey: ["admin-nodes"],
    queryFn: () => api<OpenVpnNode[]>(endpoints.nodes),
  });

  const nodeName = (nodeId: string | null): string =>
    nodeId == null ? "Local" : (nodes?.find((n) => n.id === nodeId)?.name ?? nodeId);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-daemons"] });

  const save = useMutation({
    mutationFn: async () => {
      const payload = {
        name: form.name || null,
        daemonIndex: Number(form.daemonIndex),
        port: form.port ? Number(form.port) : null,
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
        nodeId: form.nodeId || null,
        ipv6Enabled: form.ipv6Enabled,
        ipv6Subnet: form.ipv6Enabled ? form.ipv6Subnet || null : null,
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
      nodeId: row.nodeId ?? "",
      ipv6Enabled: row.ipv6Enabled,
      ipv6Subnet: row.ipv6Subnet ?? "fd00:1::/64",
      enabled: row.enabled,
    });
    setDialogOpen(true);
  };

  const columns: GridColDef[] = [
    {
      field: "name",
      headerName: "Daemon",
      flex: 1.2,
      minWidth: 160,
      renderCell: (params) => {
        const row = params.row as Daemon;
        return <Typography variant="body2">{row.name ?? `Daemon ${row.daemonIndex}`}</Typography>;
      },
    },
    {
      field: "endpoint",
      headerName: "Endpoint",
      width: 120,
      valueGetter: (_, row) => `${(row as Daemon).proto.toUpperCase()}:${(row as Daemon).port}`,
    },
    {
      field: "subnet",
      headerName: "Subnet",
      width: 140,
      valueGetter: (_, row) => `${(row as Daemon).subnet}/${(row as Daemon).subnetMask}`,
    },
    {
      field: "ipv6",
      headerName: "IPv6",
      width: 170,
      valueGetter: (_, row) =>
        (row as Daemon).ipv6Enabled ? (row as Daemon).ipv6Subnet ?? "enabled" : "—",
    },
    {
      field: "role",
      headerName: "Serves profiles",
      width: 170,
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
      width: 100,
      valueGetter: (_, row) => ((row as Daemon).fullTunnel ? "Full tunnel" : "Split tunnel"),
    },
    {
      field: "node",
      headerName: "Node",
      width: 140,
      valueGetter: (_, row) => nodeName((row as Daemon).nodeId),
    },
    {
      field: "enabled",
      headerName: "Enabled",
      width: 90,
      renderCell: (params) => {
        const row = params.row as Daemon;
        return (
          <Switch size="small" checked={row.enabled} onChange={() => toggleEnabled.mutate(row)} />
        );
      },
    },
    {
      field: "dco",
      headerName: "Data channel",
      width: 110,
      renderCell: (params) => {
        const row = params.row as Daemon;
        const dco = row.dco;
        return (
          <Chip
            size="small"
            color={dco === true ? "success" : dco === false ? "default" : "default"}
            variant="outlined"
            label={dcoLabel(row)}
            title={
              dco === true
                ? "Data Channel Offload (kernel)"
                : dco === false
                  ? "Userspace data channel"
                  : "Unknown until the daemon is polled"
            }
          />
        );
      },
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 90,
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
                    text: `Delete "${row.name ?? row.daemonIndex}"?${
                      row.nodeId ? "" : " Its config file will be removed."
                    }`,
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
                sx={{ width: 140 }}
                helperText="Empty = auto-assign from published range"
              />
              <TextField
                select
                label="Protocol"
                value={form.proto}
                onChange={(e) =>
                  setForm({ ...form, proto: e.target.value as "udp" | "tcp" | "udp6" | "tcp6" })
                }
                sx={{ width: 140 }}
              >
                <MenuItem value="udp">UDP</MenuItem>
                <MenuItem value="tcp">TCP</MenuItem>
                <MenuItem value="udp6">UDP6</MenuItem>
                <MenuItem value="tcp6">TCP6</MenuItem>
              </TextField>
            <Stack direction="row" spacing={2}>
              <TextField
                label="Admin host"
                value={form.adminHost}
                onChange={(e) => setForm({ ...form, adminHost: e.target.value })}
                placeholder="vpn.example.com"
                sx={{ flex: 1 }}
              />
              <TextField
                select
                label="VPN node"
                value={form.nodeId}
                onChange={(e) => setForm({ ...form, nodeId: e.target.value })}
                helperText="Empty = local deployment"
                sx={{ width: 220 }}
              >
                <MenuItem value="">Local (this server)</MenuItem>
                {(nodes ?? [])
                  .filter((n) => n.enabled)
                  .map((n) => (
                    <MenuItem key={n.id} value={n.id}>
                      {n.name}
                    </MenuItem>
                  ))}
              </TextField>
            </Stack>
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
                  checked={form.ipv6Enabled}
                  onChange={(e) => setForm({ ...form, ipv6Enabled: e.target.checked })}
                />
              }
              label="Enable IPv6 (dual-stack tunnel)"
            />
            {form.ipv6Enabled && (
              <TextField
                label="IPv6 subnet"
                value={form.ipv6Subnet}
                onChange={(e) => setForm({ ...form, ipv6Subnet: e.target.value })}
                helperText="Client subnet in CIDR form, e.g. fd00:1::/64"
              />
            )}
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
            disabled={!form.daemonIndex || !form.subnet || !form.subnetMask}
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
