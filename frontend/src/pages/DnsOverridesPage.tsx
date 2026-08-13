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
import { api, endpoints, type DnsRecordDto } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

type Scope = "GLOBAL" | "USER" | "GROUP";

interface UserRow {
  id: string;
  username: string;
}

interface GroupRow {
  id: string;
  name: string;
}

interface DnsForm {
  hostname: string;
  ipv4: string;
  scope: Scope;
  scopeId: string;
  enabled: boolean;
}

const EMPTY_FORM: DnsForm = {
  hostname: "",
  ipv4: "",
  scope: "GLOBAL",
  scopeId: "",
  enabled: true,
};

export function DnsOverridesPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<DnsForm>(EMPTY_FORM);
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);

  const { data: records, isLoading } = useQuery<DnsRecordDto[]>({
    queryKey: ["admin-dns-overrides"],
    queryFn: () => api<DnsRecordDto[]>(endpoints.dnsOverrides),
  });

  const { data: users } = useQuery<UserRow[]>({
    queryKey: ["admin-users", ""],
    queryFn: () => api<UserRow[]>(endpoints.users),
  });

  const { data: groups } = useQuery<GroupRow[]>({
    queryKey: ["admin-groups"],
    queryFn: () => api<GroupRow[]>(endpoints.groups),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-dns-overrides"] });

  const save = useMutation({
    mutationFn: async () => {
      const payload = {
        hostname: form.hostname.trim().toLowerCase(),
        ipv4: form.ipv4.trim(),
        scope: form.scope,
        scopeId: form.scope === "GLOBAL" ? null : form.scopeId,
        enabled: form.enabled,
      };
      if (editing) {
        return api(endpoints.dnsOverrides + `/${editing}`, { method: "PUT", body: JSON.stringify(payload) });
      }
      return api(endpoints.dnsOverrides, { method: "POST", body: JSON.stringify(payload) });
    },
    onSuccess: () => {
      toast.success(editing ? "DNS override updated" : "DNS override created");
      setDialogOpen(false);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const toggleEnabled = useMutation({
    mutationFn: (row: DnsRecordDto) =>
      api(endpoints.dnsOverrides + `/${row.id}/enabled`, {
        method: "POST",
        body: JSON.stringify(!row.enabled),
      }),
    onSuccess: () => {
      toast.success("DNS override updated");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => api(endpoints.dnsOverrides + `/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      toast.success("DNS override deleted");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (row: DnsRecordDto) => {
    setEditing(row.id);
    setForm({
      hostname: row.hostname,
      ipv4: row.ipv4,
      scope: row.scope,
      scopeId: row.scopeId ?? "",
      enabled: row.enabled,
    });
    setDialogOpen(true);
  };

  const scopeOptions =
    form.scope === "USER"
      ? (users ?? []).map((u) => ({ id: u.id, label: u.username }))
      : (groups ?? []).map((g) => ({ id: g.id, label: g.name }));

  const columns: GridColDef[] = [
    { field: "hostname", headerName: "Hostname", flex: 1.4, minWidth: 160 },
    { field: "ipv4", headerName: "Address", width: 140 },
    {
      field: "scope",
      headerName: "Scope",
      flex: 1,
      minWidth: 150,
      valueGetter: (_, row) => {
        const r = row as DnsRecordDto;
        if (r.scope === "GLOBAL") return "All users";
        return `${r.scope === "USER" ? "User" : "Group"}: ${r.scopeName ?? ""}`;
      },
      renderCell: (params) => {
        const r = params.row as DnsRecordDto;
        const color = r.scope === "GLOBAL" ? "default" : r.scope === "USER" ? "primary" : "secondary";
        return <Chip label={params.value} size="small" color={color} variant="outlined" />;
      },
    },
    {
      field: "enabled",
      headerName: "Enabled",
      width: 90,
      renderCell: (params) => {
        const row = params.row as DnsRecordDto;
        return <Switch size="small" checked={row.enabled} onChange={() => toggleEnabled.mutate(row)} />;
      },
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 90,
      sortable: false,
      filterable: false,
      renderCell: (params) => {
        const row = params.row as DnsRecordDto;
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
                onClick={() =>
                  setConfirm({
                    title: "Delete DNS override",
                    text: `Delete the override for ${row.hostname}?`,
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
          DNS overrides
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
          New override
        </Button>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Internal hostnames answered by the VPN resolver, so they resolve only while connected (public DNS
        returns no answer off-VPN). GLOBAL overrides apply to all users; GROUP/USER-scoped overrides
        resolve for everyone but only the target scope can reach the address.
      </Typography>
      <Paper sx={{ height: 620 }}>
        <DataGrid
          rows={records ?? []}
          columns={columns}
          loading={isLoading}
          pagination
          pageSizeOptions={[10, 25, 50]}
          initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
        />
      </Paper>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? "Edit DNS override" : "New DNS override"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Hostname"
              value={form.hostname}
              onChange={(e) => setForm({ ...form, hostname: e.target.value })}
              placeholder="e.g. git.internal"
              helperText="Lowercase hostname/domain served only over the VPN."
              required
            />
            <TextField
              label="IPv4 address"
              value={form.ipv4}
              onChange={(e) => setForm({ ...form, ipv4: e.target.value })}
              placeholder="e.g. 10.10.0.5"
              helperText="The address dnsmasq returns for the hostname."
              required
            />
            <TextField
              select
              label="Scope"
              value={form.scope}
              onChange={(e) => setForm({ ...form, scope: e.target.value as Scope, scopeId: "" })}
            >
              <MenuItem value="GLOBAL">All users</MenuItem>
              <MenuItem value="USER">A specific user</MenuItem>
              <MenuItem value="GROUP">A group</MenuItem>
            </TextField>
            {form.scope !== "GLOBAL" && (
              <TextField
                select
                label={form.scope === "USER" ? "User" : "Group"}
                value={form.scopeId}
                onChange={(e) => setForm({ ...form, scopeId: e.target.value })}
                helperText="Other users can resolve this hostname but the firewall blocks their access."
                required
              >
                {scopeOptions.map((o) => (
                  <MenuItem key={o.id} value={o.id}>
                    {o.label}
                  </MenuItem>
                ))}
              </TextField>
            )}
            <FormControlLabel
              control={
                <Switch checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
              }
              label="Enabled"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!form.hostname.trim() || !form.ipv4.trim() || (form.scope !== "GLOBAL" && !form.scopeId)}
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
