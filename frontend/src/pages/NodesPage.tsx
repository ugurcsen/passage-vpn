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
import { api, endpoints, type OpenVpnNode } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

interface NodeForm {
  name: string;
  mgmtHost: string;
  mgmtPortBase: string;
  adminIp: string;
  adminHost: string;
  enabled: boolean;
}

const EMPTY_FORM: NodeForm = {
  name: "",
  mgmtHost: "",
  mgmtPortBase: "7505",
  adminIp: "",
  adminHost: "",
  enabled: true,
};

export function NodesPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<NodeForm>(EMPTY_FORM);
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);

  const { data: nodes, isLoading } = useQuery<OpenVpnNode[]>({
    queryKey: ["admin-nodes"],
    queryFn: () => api<OpenVpnNode[]>(endpoints.nodes),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-nodes"] });

  const save = useMutation({
    mutationFn: async () => {
      const payload = {
        name: form.name.trim().toLowerCase(),
        mgmtHost: form.mgmtHost.trim(),
        mgmtPortBase: Number(form.mgmtPortBase),
        adminIp: form.adminIp.trim() || null,
        adminHost: form.adminHost.trim() || null,
        enabled: form.enabled,
      };
      if (editing) {
        return api(endpoints.nodes + `/${editing}`, { method: "PUT", body: JSON.stringify(payload) });
      }
      return api(endpoints.nodes, { method: "POST", body: JSON.stringify(payload) });
    },
    onSuccess: () => {
      toast.success(editing ? "Node updated" : "Node registered");
      setDialogOpen(false);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const toggleEnabled = useMutation({
    mutationFn: (row: OpenVpnNode) =>
      api(endpoints.nodes + `/${row.id}/enabled?enabled=${!row.enabled}`, { method: "POST" }),
    onSuccess: () => {
      toast.success("Node updated");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => api(endpoints.nodes + `/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      toast.success("Node removed");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (row: OpenVpnNode) => {
    setEditing(row.id);
    setForm({
      name: row.name,
      mgmtHost: row.mgmtHost,
      mgmtPortBase: String(row.mgmtPortBase),
      adminIp: row.adminIp ?? "",
      adminHost: row.adminHost ?? "",
      enabled: row.enabled,
    });
    setDialogOpen(true);
  };

  const columns: GridColDef[] = [
    { field: "name", headerName: "Name", flex: 1, minWidth: 140 },
    { field: "mgmtHost", headerName: "Management host", flex: 1.4, minWidth: 180 },
    { field: "mgmtPortBase", headerName: "Port base", width: 100 },
    {
      field: "adminIp",
      headerName: "Admin IP",
      width: 130,
      valueGetter: (_, row) => (row as OpenVpnNode).adminIp ?? "",
      renderCell: (params) =>
        params.value ? (
          <Typography variant="body2">{params.value as string}</Typography>
        ) : (
          <Typography variant="body2" color="text.secondary">
            —
          </Typography>
        ),
    },
    {
      field: "adminHost",
      headerName: "Admin host",
      width: 150,
      valueGetter: (_, row) => (row as OpenVpnNode).adminHost ?? "",
      renderCell: (params) =>
        params.value ? (
          <Typography variant="body2">{params.value as string}</Typography>
        ) : (
          <Typography variant="body2" color="text.secondary">
            —
          </Typography>
        ),
    },
    {
      field: "online",
      headerName: "Status",
      width: 110,
      renderCell: (params) => {
        const row = params.row as OpenVpnNode;
        return row.enabled ? (
          <Chip
            label={row.online ? "Online" : "Offline"}
            size="small"
            color={row.online ? "success" : "default"}
            variant="outlined"
          />
        ) : (
          <Chip label="Disabled" size="small" variant="outlined" />
        );
      },
    },
    {
      field: "enabled",
      headerName: "Enabled",
      width: 90,
      renderCell: (params) => {
        const row = params.row as OpenVpnNode;
        return (
          <Switch
            size="small"
            checked={row.enabled}
            onChange={() => toggleEnabled.mutate(row)}
            inputProps={{ name: "enabled", "aria-label": `Toggle enabled for ${row.name}` }}
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
        const row = params.row as OpenVpnNode;
        return (
          <Stack direction="row">
            <Tooltip title="Edit">
              <IconButton size="small" aria-label="Edit node" onClick={() => openEdit(row)}>
                <EditIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="Remove">
              <IconButton
                size="small"
                aria-label="Remove node"
                onClick={() =>
                  setConfirm({
                    title: "Remove VPN node",
                    text: `Remove node ${row.name}? Traffic history is kept, but monitoring for this node stops.`,
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

  const portInvalid = !/^\d+$/.test(form.mgmtPortBase) || Number(form.mgmtPortBase) < 1 || Number(form.mgmtPortBase) > 65535;

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="h5" fontWeight={700}>
          VPN nodes
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
          Register node
        </Button>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        The local deployment is always managed and never listed. Register remote gateway nodes so the
        central backend can probe their OpenVPN management interfaces and route status, kill and
        monitoring requests per node.
      </Typography>
      <Paper sx={{ height: 560 }}>
        <DataGrid
          rows={nodes ?? []}
          columns={columns}
          loading={isLoading}
          pagination
          pageSizeOptions={[10, 25, 50]}
          initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
        />
      </Paper>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? "Edit VPN node" : "Register VPN node"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              id="node-name"
              name="name"
              label="Name"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. edge-eu"
              helperText="Lowercase unique identifier for this gateway."
              required
            />
            <TextField
              id="node-mgmt-host"
              name="mgmtHost"
              label="Management host"
              value={form.mgmtHost}
              onChange={(e) => setForm({ ...form, mgmtHost: e.target.value })}
              placeholder="e.g. vpn-eu.example.com"
              helperText="Hostname/IP the central backend uses to reach the node's OpenVPN management port."
              required
            />
            <TextField
              id="node-mgmt-port-base"
              name="mgmtPortBase"
              label="Management port base"
              type="number"
              value={form.mgmtPortBase}
              onChange={(e) => setForm({ ...form, mgmtPortBase: e.target.value })}
              helperText="First management port; daemon N listens on port base + N (7505, 7506, ...)."
              required
              error={portInvalid}
            />
            <TextField
              id="node-admin-ip"
              name="adminIp"
              label="Admin IP (optional)"
              value={form.adminIp}
              onChange={(e) => setForm({ ...form, adminIp: e.target.value })}
              placeholder="e.g. 10.0.0.5"
              helperText="Network address used for management plane access to this gateway."
            />
            <TextField
              id="node-admin-host"
              name="adminHost"
              label="Admin host (optional)"
              value={form.adminHost}
              onChange={(e) => setForm({ ...form, adminHost: e.target.value })}
              placeholder="e.g. vpn-eu.example.com"
              helperText="Public host advertised in connection profiles for this node's daemons. Falls back to the global VPN host."
            />
            <FormControlLabel
              control={
                <Switch
                  id="node-enabled"
                  name="enabled"
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
            disabled={!form.name.trim() || !form.mgmtHost.trim() || portInvalid}
            onClick={() => save.mutate()}
          >
            {editing ? "Save" : "Register"}
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger
        confirmLabel="Remove"
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
