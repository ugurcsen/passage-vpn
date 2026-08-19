import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Box,
  Button,
  Chip,
  InputAdornment,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { DataGrid, type GridRowSelectionModel } from "@mui/x-data-grid";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import BlockIcon from "@mui/icons-material/Block";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import SearchIcon from "@mui/icons-material/Search";
import { api, endpoints } from "@/lib/api";
import { useAuth } from "@/hooks/useAuth";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import {
  type DeleteOptions,
  type UserRow,
  type GroupRow,
  type UserForm,
  EMPTY_DELETE_OPTIONS,
  EMPTY_FORM,
} from "./types";
import { getUserColumns } from "./UserColumns";
import { UserFormDialog } from "./UserFormDialog";
import { MfaDialog } from "./MfaDialog";
import { CcdSettingsDialog } from "./CcdSettingsDialog";
import { DeleteDialog } from "./DeleteDialog";
import { ResetPasswordDialog } from "./ResetPasswordDialog";

interface DeleteTarget {
  ids: string[];
  usernames: string;
}

export function UsersPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const { user: currentUser } = useAuth();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<UserForm>(EMPTY_FORM);
  const [confirm, setConfirm] = useState<{
    title: string;
    text: string;
    confirmLabel: string;
    action: () => void;
  } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget | null>(null);
  const [deleteOptions, setDeleteOptions] = useState<DeleteOptions>(EMPTY_DELETE_OPTIONS);
  const [resetTarget, setResetTarget] = useState<UserRow | null>(null);
  const [resetPassword, setResetPassword] = useState("");
  const [selection, setSelection] = useState<GridRowSelectionModel>([]);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<"all" | "active" | "disabled">("all");
  const [mfaTarget, setMfaTarget] = useState<UserRow | null>(null);
  const [ccdTarget, setCcdTarget] = useState<UserRow | null>(null);

  const isAdmin = currentUser?.role === "ADMIN";
  const canManageRow = (row: UserRow) => isAdmin || row.role === "USER";

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
        adminGroupIds: form.role === "GROUP_ADMIN" ? form.adminGroupIds : null,
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
    onSuccess: () => { toast.success("User status updated"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const resetMutation = useMutation({
    mutationFn: () =>
      api(endpoints.users + `/${resetTarget?.id}/reset-password`, {
        method: "POST",
        body: JSON.stringify({ password: resetPassword }),
      }),
    onSuccess: () => { toast.success("Password reset"); setResetTarget(null); setResetPassword(""); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Reset failed"),
  });

  const deleteMutation = useMutation({
    mutationFn: ({ id, options }: { id: string; options: DeleteOptions }) =>
      api(endpoints.users + `/${id}`, { method: "DELETE", body: JSON.stringify(options) }),
    onSuccess: () => { toast.success("User deleted"); setDeleteTarget(null); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const bulkMutation = useMutation({
    mutationFn: ({ action, ids, options }: { action: "ban" | "unban" | "delete"; ids: string[]; options?: DeleteOptions }) =>
      api(endpoints.users + "/bulk", {
        method: "POST",
        body: JSON.stringify({ action: action.toUpperCase(), ids, ...(options ? { options } : {}) }),
      }),
    onSuccess: (_data, vars) => {
      toast.success(`${vars.ids.length} user${vars.ids.length === 1 ? "" : "s"} ${vars.action === "delete" ? "deleted" : vars.action + "ed"}`);
      setSelection([]);
      setDeleteTarget(null);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Bulk operation failed"),
  });

  const openDeleteDialog = (ids: string[], usernames: string) => {
    setDeleteOptions(EMPTY_DELETE_OPTIONS);
    setDeleteTarget({ ids, usernames });
  };

  const confirmBulk = (action: "ban" | "unban" | "delete") => {
    const ids = selection.map(String);
    const config = { ban: { verb: "Disable", label: "Disable" }, unban: { verb: "Enable", label: "Enable" }, delete: { verb: "Delete", label: "Delete" } } as const;
    const { verb, label } = config[action];
    if (action === "delete") {
      openDeleteDialog(ids, `${ids.length} selected user${ids.length === 1 ? "" : "s"}`);
      return;
    }
    setConfirm({
      title: `Bulk ${label.toLowerCase()}`,
      text: `${verb} ${ids.length} selected user${ids.length === 1 ? "" : "s"}?`,
      confirmLabel: label,
      action: () => bulkMutation.mutate({ action, ids }),
    });
  };

  const openCreate = () => { setEditing(null); setForm(EMPTY_FORM); setDialogOpen(true); };
  const openEdit = (row: UserRow) => {
    setEditing(row.id);
    setForm({
      username: row.username,
      password: "",
      fullName: row.fullName ?? "",
      email: row.email ?? "",
      role: row.role,
      groupIds: groups?.filter((g) => row.groups.includes(g.name)).map((g) => g.id) ?? [],
      adminGroupIds: row.adminGroupIds ?? [],
    });
    setDialogOpen(true);
  };

  const columns = getUserColumns({
    isAdmin,
    canManageRow,
    onEdit: openEdit,
    onBan: (row) => banMutation.mutate(row),
    onResetPassword: setResetTarget,
    onCcdSettings: setCcdTarget,
    onManageMfa: setMfaTarget,
    onDelete: openDeleteDialog,
  });

  const filteredRows = (users ?? []).filter((u) =>
    statusFilter === "all" ? true : statusFilter === "active" ? !u.banned : u.banned,
  );

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="h5" fontWeight={700}>Users</Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>New user</Button>
      </Box>
      <Stack direction="row" spacing={1.5} sx={{ mb: 2 }} alignItems="center" flexWrap="wrap">
        <TextField
          size="small"
          placeholder="Search username, name, email\u2026"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          sx={{ width: 320 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment>
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
              <Button size="small" variant="outlined" color="warning" startIcon={<BlockIcon />} disabled={bulkMutation.isPending} onClick={() => confirmBulk("ban")}>Disable</Button>
              <Button size="small" variant="outlined" color="success" startIcon={<CheckCircleIcon />} disabled={bulkMutation.isPending} onClick={() => confirmBulk("unban")}>Enable</Button>
              <Button size="small" variant="outlined" color="error" startIcon={<DeleteIcon />} disabled={bulkMutation.isPending} onClick={() => confirmBulk("delete")}>Delete</Button>
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
          isRowSelectable={(params) => canManageRow(params.row as UserRow)}
          onRowSelectionModelChange={setSelection}
        />
      </Paper>

      <UserFormDialog
        open={dialogOpen}
        editing={editing}
        form={form}
        isAdmin={isAdmin}
        groups={groups ?? []}
        onChange={(patch) => setForm((f) => ({ ...f, ...patch }))}
        onClose={() => setDialogOpen(false)}
        onSave={() => saveUser.mutate()}
      />

      <ResetPasswordDialog
        target={resetTarget}
        password={resetPassword}
        onPasswordChange={setResetPassword}
        onClose={() => setResetTarget(null)}
        onReset={() => resetMutation.mutate()}
      />

      <MfaDialog
        target={mfaTarget}
        onClose={() => setMfaTarget(null)}
        onSaved={invalidate}
      />

      <CcdSettingsDialog
        target={ccdTarget}
        onClose={() => setCcdTarget(null)}
        onSaved={invalidate}
      />

      <DeleteDialog
        open={!!deleteTarget}
        target={deleteTarget}
        options={deleteOptions}
        isPending={deleteMutation.isPending || bulkMutation.isPending}
        onOptionsChange={(patch) => setDeleteOptions((o) => ({ ...o, ...patch }))}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => {
          if (!deleteTarget) return;
          const options = deleteOptions;
          if (deleteTarget.ids.length === 1) {
            deleteMutation.mutate({ id: deleteTarget.ids[0], options });
          } else {
            bulkMutation.mutate({ action: "delete", ids: deleteTarget.ids, options });
          }
        }}
      />

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger={confirm?.confirmLabel === "Delete"}
        confirmLabel={confirm?.confirmLabel ?? "Confirm"}
        loading={bulkMutation.isPending || deleteMutation.isPending}
        onCancel={() => setConfirm(null)}
        onConfirm={() => { confirm?.action(); setConfirm(null); }}
      />
    </Box>
  );
}
