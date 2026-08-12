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
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

type TargetType = "GLOBAL" | "USER" | "GROUP";
type Action = "ALLOW" | "DENY";
type Protocol = "TCP" | "UDP" | null;

interface RuleRow {
  id: string;
  targetType: TargetType;
  targetId: string | null;
  targetName: string | null;
  action: Action;
  protocol: Protocol;
  dstCidr: string | null;
  dstGroupId: string | null;
  dstGroupName: string | null;
  dstPort: number | null;
  enabled: boolean;
  priority: number;
}

interface UserRow {
  id: string;
  username: string;
}

interface GroupRow {
  id: string;
  name: string;
}

interface RuleForm {
  targetType: TargetType;
  targetId: string;
  action: Action;
  protocol: Protocol;
  dstCidr: string;
  dstGroupId: string;
  dstPort: string;
  enabled: boolean;
}

const EMPTY_FORM: RuleForm = {
  targetType: "GLOBAL",
  targetId: "",
  action: "ALLOW",
  protocol: "TCP",
  dstCidr: "",
  dstGroupId: "",
  dstPort: "",
  enabled: true,
};

export function AccessRulesPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<RuleForm>(EMPTY_FORM);
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);

  const { data: rules, isLoading } = useQuery<RuleRow[]>({
    queryKey: ["admin-rules"],
    queryFn: () => api<RuleRow[]>(endpoints.rules),
  });

  const { data: users } = useQuery<UserRow[]>({
    queryKey: ["admin-users", ""],
    queryFn: () => api<UserRow[]>(endpoints.users),
  });

  const { data: groups } = useQuery<GroupRow[]>({
    queryKey: ["admin-groups"],
    queryFn: () => api<GroupRow[]>(endpoints.groups),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-rules"] });

  const save = useMutation({
    mutationFn: async () => {
      const payload = {
        targetType: form.targetType,
        targetId: form.targetType === "GLOBAL" ? null : form.targetId,
        action: form.action,
        protocol: form.protocol,
        dstCidr: form.dstCidr || null,
        dstGroupId: form.dstGroupId || null,
        dstPort: form.dstPort ? Number(form.dstPort) : null,
        enabled: form.enabled,
      };
      if (editing) {
        return api(endpoints.rules + `/${editing}`, { method: "PUT", body: JSON.stringify(payload) });
      }
      return api(endpoints.rules, { method: "POST", body: JSON.stringify(payload) });
    },
    onSuccess: () => {
      toast.success(editing ? "Rule updated" : "Rule created");
      setDialogOpen(false);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const toggleEnabled = useMutation({
    mutationFn: (row: RuleRow) =>
      api(`${endpoints.rules}/${row.id}/enabled?enabled=${!row.enabled}`, { method: "POST" }),
    onSuccess: () => {
      toast.success("Rule updated");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => api(endpoints.rules + `/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      toast.success("Rule deleted");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (row: RuleRow) => {
    setEditing(row.id);
    setForm({
      targetType: row.targetType,
      targetId: row.targetId ?? "",
      action: row.action,
      protocol: row.protocol,
      dstCidr: row.dstCidr ?? "",
      dstGroupId: row.dstGroupId ?? "",
      dstPort: row.dstPort?.toString() ?? "",
      enabled: row.enabled,
    });
    setDialogOpen(true);
  };

  const targetOptions =
    form.targetType === "USER"
      ? (users ?? []).map((u) => ({ id: u.id, label: u.username }))
      : (groups ?? []).map((g) => ({ id: g.id, label: g.name }));

  const columns: GridColDef[] = [
    {
      field: "target",
      headerName: "Applies to",
      flex: 1.2,
      minWidth: 130,
      valueGetter: (_, row) => {
        const r = row as RuleRow;
        return r.targetType === "GLOBAL" ? "All users" : `${r.targetType === "USER" ? "User" : "Group"}: ${r.targetName ?? ""}`;
      },
      renderCell: (params) => {
        const r = params.row as RuleRow;
        const color = r.targetType === "GLOBAL" ? "default" : r.targetType === "USER" ? "primary" : "secondary";
        return <Chip label={params.value} size="small" color={color} variant="outlined" />;
      },
    },
    {
      field: "action",
      headerName: "Action",
      width: 90,
      renderCell: (params) => (
        <Chip label={params.value as string} size="small" color={(params.value as Action) === "ALLOW" ? "success" : "error"} />
      ),
    },
    {
      field: "protocol",
      headerName: "Protocol",
      width: 100,
      valueGetter: (_, row) => (row as RuleRow).protocol ?? "any",
    },
    {
      field: "destination",
      headerName: "Destination",
      flex: 1.2,
      minWidth: 140,
      valueGetter: (_, row) => {
        const r = row as RuleRow;
        const dest = r.dstGroupName
          ? `Group: ${r.dstGroupName}`
          : (r.dstCidr ?? "any");
        return r.dstPort ? `${dest}:${r.dstPort}` : dest;
      },
    },
    { field: "priority", headerName: "Priority", width: 80 },
    {
      field: "enabled",
      headerName: "Enabled",
      width: 90,
      renderCell: (params) => {
        const row = params.row as RuleRow;
        return (
          <Switch
            size="small"
            checked={row.enabled}
            onChange={() => toggleEnabled.mutate(row)}
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
        const row = params.row as RuleRow;
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
                    title: "Delete rule",
                    text: "Delete this access rule?",
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
          Access rules
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
          New rule
        </Button>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Per-user and per-group firewall rules. When a user has any rule, their VPN traffic defaults to
        deny and ALLOW rules open specific flows. Rules apply in priority order (lowest first).
      </Typography>
      <Paper sx={{ height: 620 }}>
        <DataGrid
          rows={rules ?? []}
          columns={columns}
          loading={isLoading}
          pagination
          pageSizeOptions={[10, 25, 50]}
          initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
        />
      </Paper>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? "Edit rule" : "New access rule"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              select
              label="Applies to"
              value={form.targetType}
              onChange={(e) => setForm({ ...form, targetType: e.target.value as TargetType, targetId: "" })}
            >
              <MenuItem value="GLOBAL">All users</MenuItem>
              <MenuItem value="USER">A specific user</MenuItem>
              <MenuItem value="GROUP">A group</MenuItem>
            </TextField>
            {form.targetType !== "GLOBAL" && (
              <TextField
                select
                label={form.targetType === "USER" ? "User" : "Group"}
                value={form.targetId}
                onChange={(e) => setForm({ ...form, targetId: e.target.value })}
                required
              >
                {targetOptions.map((o) => (
                  <MenuItem key={o.id} value={o.id}>
                    {o.label}
                  </MenuItem>
                ))}
              </TextField>
            )}
            <TextField
              select
              label="Action"
              value={form.action}
              onChange={(e) => setForm({ ...form, action: e.target.value as Action })}
            >
              <MenuItem value="ALLOW">Allow</MenuItem>
              <MenuItem value="DENY">Deny</MenuItem>
            </TextField>
            <Stack direction="row" spacing={2}>
              <TextField
                select
                label="Protocol"
                value={form.protocol ?? ""}
                onChange={(e) => setForm({ ...form, protocol: (e.target.value as Protocol) || null })}
                sx={{ width: 140 }}
              >
                <MenuItem value="">
                  <em>Any</em>
                </MenuItem>
                <MenuItem value="TCP">TCP</MenuItem>
                <MenuItem value="UDP">UDP</MenuItem>
              </TextField>
              <TextField
                label="Destination port"
                value={form.dstPort}
                onChange={(e) => setForm({ ...form, dstPort: e.target.value })}
                sx={{ width: 160 }}
                placeholder="e.g. 443"
              />
            </Stack>
            <TextField
              select
              label="Destination group (empty = none)"
              value={form.dstGroupId}
              onChange={(e) => setForm({ ...form, dstGroupId: e.target.value, dstCidr: "" })}
              helperText="Targets the group's allocated subnet (static IP pool or member IPs)."
              disabled={!!form.dstCidr}
            >
              <MenuItem value="">
                <em>None</em>
              </MenuItem>
              {(groups ?? []).map((g) => (
                <MenuItem key={g.id} value={g.id}>
                  {g.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Destination CIDR (empty = any)"
              value={form.dstCidr}
              onChange={(e) => setForm({ ...form, dstCidr: e.target.value, dstGroupId: "" })}
              placeholder="e.g. 10.0.0.0/8"
              helperText="The client's own VPN IP is always the source; rules target destinations."
              disabled={!!form.dstGroupId}
            />
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
            disabled={form.targetType !== "GLOBAL" && !form.targetId}
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
