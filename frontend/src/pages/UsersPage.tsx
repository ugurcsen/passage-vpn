import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  InputAdornment,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef, type GridRowSelectionModel } from "@mui/x-data-grid";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import BlockIcon from "@mui/icons-material/Block";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import ClearIcon from "@mui/icons-material/Clear";
import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import KeyIcon from "@mui/icons-material/Key";
import LockResetIcon from "@mui/icons-material/LockReset";
import SearchIcon from "@mui/icons-material/Search";
import TuneIcon from "@mui/icons-material/Tune";
import VerifiedUserIcon from "@mui/icons-material/VerifiedUser";
import { api, copyToClipboard, endpoints, type MfaSetup } from "@/lib/api";
import { useAuth } from "@/hooks/useAuth";
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
  staticIp?: string;
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
  const [resetTarget, setResetTarget] = useState<UserRow | null>(null);
  const [resetPassword, setResetPassword] = useState("");
  const [selection, setSelection] = useState<GridRowSelectionModel>([]);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<"all" | "active" | "disabled">("all");
  const [mfaTarget, setMfaTarget] = useState<UserRow | null>(null);
  const [mfaSetup, setMfaSetup] = useState<MfaSetup | null>(null);
  const [mfaCode, setMfaCode] = useState("");
  const [mfaDisableConfirm, setMfaDisableConfirm] = useState(false);
  const [ccdTarget, setCcdTarget] = useState<UserRow | null>(null);
  const [ccdDns, setCcdDns] = useState("");
  const [ccdDomain, setCcdDomain] = useState("");
  const [ccdRoutes, setCcdRoutes] = useState("");
  const [ccdMfaOnConnect, setCcdMfaOnConnect] = useState(false);
  const [ccdTunnelMode, setCcdTunnelMode] = useState("" as "" | "full" | "split");
  const [ccdStaticIp, setCcdStaticIp] = useState("");

  const canManageMfa = currentUser?.role === "ADMIN";

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

  const mfaSetupMutation = useMutation({
    mutationFn: (row: UserRow) =>
      api<MfaSetup>(endpoints.users + `/${row.id}/mfa/setup`, { method: "POST" }),
    onSuccess: (data) => {
      setMfaSetup(data);
      setMfaCode("");
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "MFA setup failed"),
  });

  const mfaEnableMutation = useMutation({
    mutationFn: () =>
      api(endpoints.users + `/${mfaTarget?.id}/mfa/enable`, {
        method: "POST",
        body: JSON.stringify({ code: mfaCode }),
      }),
    onSuccess: () => {
      toast.success("MFA enabled");
      closeMfaDialog();
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Enable failed"),
  });

  const mfaDisableMutation = useMutation({
    mutationFn: () =>
      api(endpoints.users + `/${mfaTarget?.id}/mfa/disable`, { method: "POST" }),
    onSuccess: () => {
      toast.success("MFA disabled");
      setMfaDisableConfirm(false);
      closeMfaDialog();
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Disable failed"),
  });

  const openMfaDialog = (row: UserRow) => {
    setMfaTarget(row);
    setMfaSetup(null);
    setMfaCode("");
    setMfaDisableConfirm(false);
  };

  const closeMfaDialog = () => {
    setMfaTarget(null);
    setMfaSetup(null);
    setMfaCode("");
    setMfaDisableConfirm(false);
  };

  const copySecret = async () => {
    if (!mfaSetup) return;
    const ok = await copyToClipboard(mfaSetup.secret);
    if (ok) toast.success("Secret copied");
    else toast.error("Copy failed");
  };

  const openCcdEditor = async (row: UserRow) => {
    setCcdTarget(row);
    setCcdDns("");
    setCcdDomain("");
    setCcdRoutes("");
    setCcdMfaOnConnect(false);
    setCcdTunnelMode("");
    setCcdStaticIp(row.staticIp ?? "");
    try {
      const settings = await api<Record<string, unknown>>(
        endpoints.users + `/${row.id}/settings`,
      );
      setCcdDns(Array.isArray(settings.dns_servers) ? settings.dns_servers.join(", ") : String(settings.dns_servers ?? ""));
      setCcdDomain(String(settings.dns_domain ?? ""));
      setCcdRoutes(Array.isArray(settings.route_restriction) ? settings.route_restriction.join(", ") : String(settings.route_restriction ?? ""));
      setCcdMfaOnConnect(settings.require_mfa_on_connect === true);
      const mode = settings.tunnel_mode;
      setCcdTunnelMode(mode === "full" || mode === "split" ? mode : "");
    } catch {
      toast.error("Failed to load per-user settings");
    }
  };

  const closeCcdEditor = () => setCcdTarget(null);

  const saveCcdSettings = useMutation({
    mutationFn: async () => {
      if (!ccdTarget) return;
      const base = endpoints.users + `/${ccdTarget.id}/settings`;
      await api(base + "/dns_servers", { method: "PUT", body: JSON.stringify(ccdDns) });
      await api(base + "/dns_domain", { method: "PUT", body: JSON.stringify(ccdDomain) });
      await api(base + "/route_restriction", { method: "PUT", body: JSON.stringify(ccdRoutes) });
      await api(base + "/require_mfa_on_connect", {
        method: "PUT",
        body: JSON.stringify(ccdMfaOnConnect),
      });
      await api(base + "/tunnel_mode", { method: "PUT", body: JSON.stringify(ccdTunnelMode) });
    },
    onSuccess: () => {
      toast.success("Per-user settings saved");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const saveStaticIp = useMutation({
    mutationFn: async (ip: string) => {
      if (!ccdTarget) return;
      if (ip.trim()) {
        await api(endpoints.users + `/${ccdTarget.id}/static-ip`, {
          method: "PUT",
          body: JSON.stringify({ staticIp: ip.trim() }),
        });
      } else {
        await api(endpoints.users + `/${ccdTarget.id}/static-ip`, { method: "DELETE" });
      }
    },
    onSuccess: () => {
      toast.success("Static IP updated");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const allocateStaticIp = useMutation({
    mutationFn: async () => {
      if (!ccdTarget) return;
      return api(endpoints.users + `/${ccdTarget.id}/static-ip/allocate`, { method: "POST" });
    },
    onSuccess: (updated) => {
      toast.success("Static IP allocated");
      const row = updated as unknown as UserRow;
      setCcdStaticIp(row.staticIp ?? "");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Allocation failed"),
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
    { field: "fullName", headerName: "Full name", flex: 1, minWidth: 100 },
    {
      field: "groups",
      headerName: "Groups",
      width: 160,
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
      width: 100,
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
      width: 70,
      renderCell: (params) =>
        params.value ? <Chip label="On" size="small" color="success" /> : <Chip label="Off" size="small" />,
    },
    {
      field: "banned",
      headerName: "Status",
      width: 100,
      renderCell: (params) =>
        params.value ? <Chip label="Disabled" size="small" color="error" /> : <Chip label="Active" size="small" color="success" />,
    },
    {
      field: "lastLoginAt",
      headerName: "Last login",
      width: 150,
      valueGetter: (_, row) => (row as UserRow).lastLoginAt ?? "",
      renderCell: (params) => <Typography variant="body2">{formatDateTime(params.value as string)}</Typography>,
    },
    {
      field: "staticIp",
      headerName: "Static IP",
      width: 130,
      valueGetter: (_, row) => (row as UserRow).staticIp ?? "",
      renderCell: (params) =>
        params.value ? (
          <Chip label={params.value as string} size="small" variant="outlined" color="info" />
        ) : (
          <Typography variant="body2" color="text.secondary">
            —
          </Typography>
        ),
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 250,
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
            <Tooltip title="CCD settings">
              <IconButton size="small" onClick={() => openCcdEditor(row)} data-testid={`edit-ccd-${row.username}`}>
                <TuneIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            {canManageMfa && (
              <Tooltip title="Manage MFA">
                <IconButton
                  size="small"
                  onClick={() => openMfaDialog(row)}
                  data-testid={`manage-mfa-${row.username}`}
                >
                  <VerifiedUserIcon
                    fontSize="small"
                    color={row.mfaEnabled ? "success" : "action"}
                  />
                </IconButton>
              </Tooltip>
            )}
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

      <Dialog open={!!mfaTarget} onClose={closeMfaDialog} maxWidth="sm" fullWidth>
        <DialogTitle>Manage MFA — {mfaTarget?.username}</DialogTitle>
        <DialogContent>
          {mfaTarget?.mfaEnabled && !mfaSetup ? (
            <Stack spacing={2} sx={{ mt: 1 }}>
              <Alert severity="success">Two-factor authentication is enabled.</Alert>
              {mfaDisableConfirm ? (
                <>
                  <Alert severity="warning">
                    Disable MFA for {mfaTarget.username}? They will no longer be asked for a code at
                    sign-in.
                  </Alert>
                  <Button
                    color="error"
                    variant="contained"
                    disabled={mfaDisableMutation.isPending}
                    onClick={() => mfaDisableMutation.mutate()}
                  >
                    {mfaDisableMutation.isPending ? <CircularProgress size={18} /> : "Disable MFA"}
                  </Button>
                </>
              ) : (
                <Button color="error" variant="outlined" onClick={() => setMfaDisableConfirm(true)}>
                  Disable MFA
                </Button>
              )}
            </Stack>
          ) : mfaSetup ? (
            <Stack spacing={2} sx={{ mt: 1 }}>
              <Alert severity="info">
                Scan the QR code with Google Authenticator (or any TOTP app), then enter the
                6-digit code to enable two-factor authentication.
              </Alert>
              <Box sx={{ display: "flex", justifyContent: "center" }}>
                <img src={mfaSetup.qrDataUrl} alt="TOTP QR code" width={180} height={180} />
              </Box>
              <TextField
                label="Secret"
                value={mfaSetup.secret}
                fullWidth
                slotProps={{
                  input: {
                    readOnly: true,
                    endAdornment: (
                      <InputAdornment position="end">
                        <Tooltip title="Copy secret">
                          <IconButton size="small" onClick={copySecret}>
                            <ContentCopyIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </InputAdornment>
                    ),
                  },
                }}
              />
              <TextField
                label="Verification code"
                value={mfaCode}
                onChange={(e) => setMfaCode(e.target.value)}
                fullWidth
                inputProps={{ inputMode: "numeric", maxLength: 6 }}
                helperText="Confirm the code to finish enabling MFA."
              />
            </Stack>
          ) : (
            <Stack spacing={2} sx={{ mt: 1 }}>
              <Alert severity="info">
                Two-factor authentication is disabled for this user. Set it up to require a code
                from an authenticator app at sign-in.
              </Alert>
              <Button
                variant="contained"
                disabled={mfaSetupMutation.isPending}
                onClick={() => mfaSetupMutation.mutate(mfaTarget!)}
              >
                {mfaSetupMutation.isPending ? <CircularProgress size={18} /> : "Set up MFA"}
              </Button>
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          {mfaSetup ? (
            <>
              <Button onClick={() => setMfaSetup(null)}>Back</Button>
              <Button
                variant="contained"
                disabled={mfaCode.length < 6 || mfaEnableMutation.isPending}
                onClick={() => mfaEnableMutation.mutate()}
              >
                {mfaEnableMutation.isPending ? <CircularProgress size={18} /> : "Enable"}
              </Button>
            </>
          ) : (
            <Button onClick={closeMfaDialog}>Close</Button>
          )}
        </DialogActions>
      </Dialog>

      <Dialog open={!!ccdTarget} onClose={closeCcdEditor} maxWidth="sm" fullWidth>
        <DialogTitle>CCD settings — {ccdTarget?.username}</DialogTitle>
        <DialogContent>
          <Stack spacing={3} sx={{ mt: 1 }}>
            <Box>
              <Typography variant="subtitle2" gutterBottom>
                Static IP
              </Typography>
              <Stack direction="row" spacing={1} alignItems="center">
                <TextField
                  size="small"
                  placeholder="e.g. 10.8.0.42"
                  value={ccdStaticIp}
                  onChange={(e) => setCcdStaticIp(e.target.value)}
                  sx={{ flex: 1 }}
                  helperText={ccdTarget?.staticIp ? "Override the group pool allocation." : "Leave empty to clear."}
                />
                <Button
                  variant="outlined"
                  startIcon={<TuneIcon />}
                  disabled={saveStaticIp.isPending}
                  onClick={() => saveStaticIp.mutate(ccdStaticIp)}
                >
                  Set
                </Button>
                <Button
                  variant="contained"
                  disabled={allocateStaticIp.isPending}
                  onClick={() => allocateStaticIp.mutate()}
                  title="Allocate next free IP from the group pool"
                >
                  {allocateStaticIp.isPending ? <CircularProgress size={18} /> : "Allocate"}
                </Button>
                {ccdTarget?.staticIp && (
                  <Tooltip title="Clear static IP">
                    <IconButton
                      size="small"
                      disabled={saveStaticIp.isPending}
                      onClick={() => {
                        setCcdStaticIp("");
                        saveStaticIp.mutate("");
                      }}
                    >
                      <ClearIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
              </Stack>
            </Box>
            <Box>
              <Typography variant="subtitle2" gutterBottom>
                Per-user settings (override group/server defaults)
              </Typography>
              <Stack spacing={2}>
                <TextField
                  label="DNS servers"
                  size="small"
                  placeholder="e.g. 1.1.1.1, 8.8.8.8"
                  value={ccdDns}
                  onChange={(e) => setCcdDns(e.target.value)}
                  helperText="Comma-separated DNS servers pushed to this client."
                />
                <TextField
                  label="DNS domain"
                  size="small"
                  placeholder="e.g. vpn.example.com"
                  value={ccdDomain}
                  onChange={(e) => setCcdDomain(e.target.value)}
                  helperText="Search domain pushed to this client."
                />
                <TextField
                  label="Tunnel mode"
                  size="small"
                  select
                  value={ccdTunnelMode}
                  onChange={(e) => setCcdTunnelMode(e.target.value as "" | "full" | "split")}
                  helperText="Full routes all traffic through the VPN; split routes only the networks below. Empty inherits the group/server default."
                >
                  <MenuItem value="">Inherit default</MenuItem>
                  <MenuItem value="full">Full tunnel</MenuItem>
                  <MenuItem value="split">Split tunnel</MenuItem>
                </TextField>
                <TextField
                  label="Route restriction"
                  size="small"
                  placeholder="e.g. 10.0.0.0/8, 192.168.0.0/16"
                  value={ccdRoutes}
                  onChange={(e) => setCcdRoutes(e.target.value)}
                  helperText="Comma-separated CIDRs this client may reach. Empty allows all."
                />
                <FormControlLabel
                  control={
                    <Switch
                      checked={ccdMfaOnConnect}
                      onChange={(e) => setCcdMfaOnConnect(e.target.checked)}
                    />
                  }
                  label="Require MFA on connect"
                />
              </Stack>
            </Box>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={closeCcdEditor}>Cancel</Button>
          <Button
            variant="contained"
            disabled={saveCcdSettings.isPending}
            onClick={() => saveCcdSettings.mutate()}
          >
            {saveCcdSettings.isPending ? <CircularProgress size={18} /> : "Save"}
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
