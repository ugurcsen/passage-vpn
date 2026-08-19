import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Box,
  Button,
  Chip,
  IconButton,
  Paper,
  Stack,
  Switch,
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
import { DaemonFormDialog } from "./DaemonFormDialog";
import { EMPTY_FORM, daemonRole, dcoLabel, isValidCidr, rowToForm, splitList, type DaemonForm } from "./helpers";

export function DaemonsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<DaemonForm>(EMPTY_FORM);
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);
  const [newRoute, setNewRoute] = useState("");
  const [routeError, setRouteError] = useState<string | null>(null);

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

  const addRoute = () => {
    const trimmed = newRoute.trim();
    if (!trimmed) return;
    if (!isValidCidr(trimmed)) {
      setRouteError("Invalid CIDR format (e.g. 192.168.0.0/24 or fd00::/64)");
      return;
    }
    if (form.extraRoutes.includes(trimmed)) {
      setRouteError("Route already exists");
      return;
    }
    setForm({ ...form, extraRoutes: [...form.extraRoutes, trimmed] });
    setNewRoute("");
    setRouteError(null);
  };

  const removeRoute = (route: string) => {
    setForm({ ...form, extraRoutes: form.extraRoutes.filter((r) => r !== route) });
  };

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
        extraRoutes: form.extraRoutes,
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
    setNewRoute("");
    setRouteError(null);
    setDialogOpen(true);
  };

  const openEdit = (row: Daemon) => {
    setEditing(row.id);
    setForm(rowToForm(row));
    setNewRoute("");
    setRouteError(null);
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
          <Switch size="small" checked={row.enabled} onChange={() => toggleEnabled.mutate(row)} inputProps={{ name: "enabled", "aria-label": `Toggle enabled for ${row.name}` }} />
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

      <DaemonFormDialog
        open={dialogOpen}
        editing={!!editing}
        form={form}
        nodes={nodes ?? []}
        newRoute={newRoute}
        routeError={routeError}
        onChange={(patch) => setForm((prev) => ({ ...prev, ...patch }))}
        onAddRoute={addRoute}
        onRemoveRoute={removeRoute}
        onChangeNewRoute={setNewRoute}
        onClearRouteError={() => setRouteError(null)}
        onClose={() => setDialogOpen(false)}
        onSave={() => save.mutate()}
      />

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
