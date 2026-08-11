import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  InputAdornment,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef, type GridRowSelectionModel } from "@mui/x-data-grid";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import BlockIcon from "@mui/icons-material/Block";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import KeyIcon from "@mui/icons-material/Key";
import LockResetIcon from "@mui/icons-material/LockReset";
import SearchIcon from "@mui/icons-material/Search";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import type { Role } from "@/hooks/useAuth";

interface UserRow {
  id: string;
  username: string;
  fullName?: string;
  email?: string;
  role: Role;
  mfaEnabled: boolean;
  banned: boolean;
  mustChangePassword: boolean;
  groups: string[];
  createdAt?: string;
  lastLoginAt?: string;
}

interface GroupRow {
  id: string;
  name: string;
  description?: string;
  parentId?: string;
  memberCount: number;
}

interface UserForm {
  username: string;
  password: string;
  fullName: string;
  email: string;
  role: Role;
  groupIds: string[];
}

const EMPTY_FORM: UserForm = {
  username: "",
  password: "",
  fullName: "",
  email: "",
  role: "USER",
  groupIds: [],
};

function formatDateTime(iso?: string) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}

export function UsersPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<UserForm>(EMPTY_FORM);
  const [confirm, setConfirm] = useState<{
    title: string;
    text: string;
    confirmLabel: string;
    action: () => void;
  } | null>(null);
  const [resetTarget, setResetTarget] = useState<UserRow | null>(null);
  const [resetPassword, setResetPassword] = useState("");
  const [selection, setSelection] = useState<GridRowSelectionModel>([]);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<"all" | "active" | "disabled">("all");

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search]);

  const { data: users, isLoading } = useQuery<UserRow[]>({
    queryKey: ["admin-users", debouncedSearch],
    queryFn: () => {
      const query = debouncedSearch ? `?search=${encodeURIComponent(debouncedSearch)}` : "";
      return api<UserRow[]>(endpoints.users + query);
    },
  });

  const { data: groups } = useQuery<GroupRow[]>({
    queryKey: ["admin-groups"],
    queryFn: () => api<GroupRow[]>(endpoints.groups),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-users"] });

  const saveUser = useMutation({
    mutationFn: async () => {
      const payload = {
        username: form.username,
        password: form.password || undefined,
        fullName: form.fullName || null,
        email: form.email || null,
        role: form.role,
        groupIds: form.groupIds,
      };
      if (editing) {
        return api(endpoints.users + `/${editing}`, { method: "PUT", body: JSON.stringify(payload) });
      }
      return api(endpoints.users, { method: "POST", body: JSON.stringify(payload) });
    },
    onSuccess: () => {
      toast.success(editing ? "User updated" : "User created");
      setDialogOpen(false);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const banMutation = useMutation({
    mutationFn: (row: UserRow) =>
      api(endpoints.users + `/${row.id}/${row.banned ? "unban" : "ban"}`, { method: "POST" }),
    onSuccess: () => {
      toast.success("User status updated");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const resetMutation = useMutation({
    mutationFn: () =>
      api(endpoints.users + `/${resetTarget?.id}/reset-password`, {
        method: "POST",
        body: JSON.stringify({ password: resetPassword }),
      }),
    onSuccess: () => {
      toast.success("Password reset");
      setResetTarget(null);
      setResetPassword("");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Reset failed"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api(endpoints.users + `/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      toast.success("User deleted");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const bulkMutation = useMutation({
    mutationFn: ({ action, ids }: { action: "ban" | "unban" | "delete"; ids: string[] }) =>
      api(endpoints.users + "/bulk", {
        method: "POST",
        body: JSON.stringify({ action: action.toUpperCase(), ids }),
      }),
    onSuccess: (_data, vars) => {
      toast.success(
        `${vars.ids.length} user${vars.ids.length === 1 ? "" : "s"} ${vars.action === "delete" ? "deleted" : vars.action + "ed"}`,
      );
      setSelection([]);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Bulk operation failed"),
  });

  const confirmBulk = (action: "ban" | "unban" | "delete") => {
    const ids = selection.map(String);
    const config = {
      ban: { verb: "Disable", label: "Disable" },
      unban: { verb: "Enable", label: "Enable" },
      delete: { verb: "Delete", label: "Delete" },
    } as const;
    const { verb, label } = config[action];
    setConfirm({
      title: `Bulk ${label.toLowerCase()}`,
      text: `${verb} ${ids.length} selected user${ids.length === 1 ? "" : "s"}?`,
      confirmLabel: label,
      action: () => bulkMutation.mutate({ action, ids }),
    });
  };

  const openCreate = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (row: UserRow) => {
    setEditing(row.id);
    setForm({
      username: row.username,
      password: "",
      fullName: row.fullName ?? "",
      email: row.email ?? "",
      role: row.role,
      groupIds: groups?.filter((g) => row.groups.includes(g.name)).map((g) => g.id) ?? [],
    });
    setDialogOpen(true);
  };

  const columns: GridColDef[] = [
    { field: "username", headerName: "Username", flex: 1.2, minWidth: 140 },
    { field: "fullName", headerName: "Full name", flex: 1 },
    {
      field: "groups",
      headerName: "Groups",
      width: 200,
      valueGetter: (_, row) => (row as UserRow).groups.join(", "),
      renderCell: (params) => (
        <Stack direction="row" spacing={0.5} sx={{ py: 0.5, flexWrap: "wrap" }}>
          {(params.value as string).split(", ").filter(Boolean).slice(0, 2).map((g) => (
            <Chip key={g} label={g} size="small" variant="outlined" />
          ))}
        </Stack>
      ),
    },
    {
      field: "role",
      headerName: "Role",
      width: 110,
      renderCell: (params) => (
        <Chip
          label={params.value as string}
          size="small"
          color={params.value === "ADMIN" ? "secondary" : params.value === "RESELLER" ? "warning" : "default"}
        />
      ),
    },
    {
      field: "mfaEnabled",
      headerName: "MFA",
      width: 80,
      renderCell: (params) =>
        params.value ? <Chip label="On" size="small" color="success" /> : <Chip label="Off" size="small" />,
    },
    {
      field: "banned",
      headerName: "Status",
      width: 110,
      renderCell: (params) =>
        params.value ? <Chip label="Disabled" size="small" color="error" /> : <Chip label="Active" size="small" color="success" />,
    },
    {
      field: "lastLoginAt",
      headerName: "Last login",
      width: 170,
      valueGetter: (_, row) => (row as UserRow).lastLoginAt ?? "",
      renderCell: (params) => <Typography variant="body2">{formatDateTime(params.value as string)}</Typography>,
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 180,
      sortable: false,
      filterable: false,
      renderCell: (params) => {
        const row = params.row as UserRow;
        return (
          <Stack direction="row">
            <Tooltip title="Edit">
              <IconButton size="small" onClick={() => openEdit(row)}>
                <LockResetIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title={row.banned ? "Enable" : "Disable"}>
              <IconButton size="small" onClick={() => banMutation.mutate(row)}>
                {row.banned ? <CheckCircleIcon fontSize="small" color="success" /> : <BlockIcon fontSize="small" />}
              </IconButton>
            </Tooltip>
            <Tooltip title="Reset password">
              <IconButton size="small" onClick={() => setResetTarget(row)}>
                <KeyIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="Delete">
              <IconButton
                size="small"
                onClick={() =>
                  setConfirm({
                    title: "Delete user",
                    text: `Delete ${row.username}? This cannot be undone.`,
                    confirmLabel: "Delete",
                    action: () => deleteMutation.mutate(row.id),
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

  const filteredRows = (users ?? []).filter((u) =>
    statusFilter === "all" ? true : statusFilter === "active" ? !u.banned : u.banned,
  );

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="h5" fontWeight={700}>
          Users
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
          New user
        </Button>
      </Box>
      <Stack direction="row" spacing={1.5} sx={{ mb: 2 }} alignItems="center" flexWrap="wrap">
        <TextField
          size="small"
          placeholder="Search username, name, email…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          sx={{ width: 320 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon fontSize="small" />
              </InputAdornment>
            ),
          }}
        />
        <TextField
          select
          size="small"
          label="Status"
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as typeof statusFilter)}
          sx={{ width: 150 }}
        >
          <MenuItem value="all">All</MenuItem>
          <MenuItem value="active">Active</MenuItem>
          <MenuItem value="disabled">Disabled</MenuItem>
        </TextField>
        {selection.length > 0 && (
          <>
            <Stack direction="row" spacing={1} sx={{ ml: 1 }}>
              <Button
                size="small"
                variant="outlined"
                color="warning"
                startIcon={<BlockIcon />}
                disabled={bulkMutation.isPending}
                onClick={() => confirmBulk("ban")}
              >
                Disable
              </Button>
              <Button
                size="small"
                variant="outlined"
                color="success"
                startIcon={<CheckCircleIcon />}
                disabled={bulkMutation.isPending}
                onClick={() => confirmBulk("unban")}
              >
                Enable
              </Button>
              <Button
                size="small"
                variant="outlined"
                color="error"
                startIcon={<DeleteIcon />}
                disabled={bulkMutation.isPending}
                onClick={() => confirmBulk("delete")}
              >
                Delete
              </Button>
            </Stack>
            <Chip label={`${selection.length} selected`} color="info" size="small" />
          </>
        )}
      </Stack>
      <Paper sx={{ height: 620 }}>
        <DataGrid
          rows={filteredRows}
          columns={columns}
          loading={isLoading}
          pagination
          pageSizeOptions={[10, 25, 50]}
          initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
          checkboxSelection
          disableRowSelectionOnClick
          onRowSelectionModelChange={setSelection}
        />
      </Paper>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? `Edit ${form.username}` : "New user"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Username"
              value={form.username}
              onChange={(e) => setForm({ ...form, username: e.target.value })}
              disabled={!!editing}
              required
            />
            <TextField
              label={editing ? "New password (blank keeps current)" : "Password"}
              type="password"
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              required={!editing}
              helperText={editing ? "At least 8 characters when changing." : "At least 8 characters."}
            />
            <TextField
              label="Full name"
              value={form.fullName}
              onChange={(e) => setForm({ ...form, fullName: e.target.value })}
            />
            <TextField
              label="Email"
              type="email"
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
            />
            <TextField
              select
              label="Role"
              value={form.role}
              onChange={(e) => setForm({ ...form, role: e.target.value as Role })}
            >
              <MenuItem value="USER">User</MenuItem>
              <MenuItem value="RESELLER">Reseller</MenuItem>
              <MenuItem value="ADMIN">Admin</MenuItem>
            </TextField>
            <TextField
              select
              label="Groups"
              value={form.groupIds}
              onChange={(e) =>
                setForm({ ...form, groupIds: e.target.value as unknown as string[] })
              }
              SelectProps={{ multiple: true }}
              helperText="Groups inherit settings resolved per user."
            >
              {(groups ?? []).map((g) => (
                <MenuItem key={g.id} value={g.id}>
                  {g.name}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!form.username || (!form.password && !editing)}
            onClick={() => saveUser.mutate()}
          >
            {editing ? "Save" : "Create"}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={!!resetTarget} onClose={() => setResetTarget(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Reset password</DialogTitle>
        <DialogContent>
          <TextField
            label={`New password for ${resetTarget?.username ?? ""}`}
            type="password"
            value={resetPassword}
            onChange={(e) => setResetPassword(e.target.value)}
            fullWidth
            sx={{ mt: 1 }}
            autoFocus
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setResetTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={resetPassword.length < 8} onClick={() => resetMutation.mutate()}>
            Reset
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger={confirm?.confirmLabel === "Delete"}
        confirmLabel={confirm?.confirmLabel ?? "Confirm"}
        loading={bulkMutation.isPending || deleteMutation.isPending}
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          confirm?.action();
          setConfirm(null);
        }}
      />
    </Box>
  );
}

export type { UserRow, GroupRow };
