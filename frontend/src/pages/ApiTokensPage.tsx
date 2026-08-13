import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import { api, copyToClipboard, endpoints, type ApiToken, type ApiTokenCreated } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

function formatDateTime(iso: string | null) {
  return iso ? new Date(iso).toLocaleString() : "—";
}

/** Admin page for API tokens used by scripted automation (admin-only). */
export function ApiTokensPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [label, setLabel] = useState("");
  const [role, setRole] = useState<"ADMIN" | "RESELLER">("ADMIN");
  const [expiresAt, setExpiresAt] = useState("");
  const [created, setCreated] = useState<ApiTokenCreated | null>(null);
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);

  const { data: tokens, isLoading } = useQuery<ApiToken[]>({
    queryKey: ["admin-api-tokens"],
    queryFn: () => api<ApiToken[]>(endpoints.apiTokens),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-api-tokens"] });

  const create = useMutation({
    mutationFn: async () => {
      const payload: Record<string, unknown> = { label: label.trim(), role };
      if (expiresAt) payload.expiresAt = new Date(expiresAt).toISOString();
      return api<ApiTokenCreated>(endpoints.apiTokens, {
        method: "POST",
        body: JSON.stringify(payload),
      });
    },
    onSuccess: (data) => {
      setCreated(data);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Create failed"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => api(`${endpoints.apiTokens}/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      toast.success("API token revoked");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const copyToken = async () => {
    if (created && (await copyToClipboard(created.rawToken))) {
      toast.success("Token copied to clipboard");
    } else {
      toast.error("Copy failed — select the token manually");
    }
  };

  const closeCreated = () => {
    setCreated(null);
    setOpen(false);
    setLabel("");
    setRole("ADMIN");
    setExpiresAt("");
  };

  const columns: GridColDef[] = [
    { field: "label", headerName: "Label", flex: 1, minWidth: 160 },
    {
      field: "prefix",
      headerName: "Token",
      width: 200,
      renderCell: (params) => <code>{params.value as string}</code>,
    },
    {
      field: "role",
      headerName: "Role",
      width: 110,
      renderCell: (params) => (
        <Chip
          size="small"
          color={(params.value as string) === "ADMIN" ? "primary" : "default"}
          variant="outlined"
          label={params.value as string}
        />
      ),
    },
    {
      field: "expiresAt",
      headerName: "Expires",
      width: 180,
      valueGetter: (_, row) => (row as ApiToken).expiresAt ?? "Never",
    },
    {
      field: "lastUsedAt",
      headerName: "Last used",
      width: 180,
      valueFormatter: (value: string | null) => formatDateTime(value),
    },
    {
      field: "createdBy",
      headerName: "Created by",
      width: 150,
      valueGetter: (_, row) => (row as ApiToken).createdBy ?? "—",
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 80,
      sortable: false,
      filterable: false,
      renderCell: (params) => {
        const row = params.row as ApiToken;
        return (
          <Tooltip title="Revoke">
            <IconButton
              size="small"
              onClick={() =>
                setConfirm({
                  title: "Revoke API token",
                  text: `Revoke "${row.label}"? Automation using this token will stop working immediately.`,
                  action: () => remove.mutate(row.id),
                })
              }
            >
              <DeleteIcon fontSize="small" color="error" />
            </IconButton>
          </Tooltip>
        );
      },
    },
  ];

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="h5" fontWeight={700}>
          API tokens
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setOpen(true)}>
          New token
        </Button>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Tokens let scripts and CI jobs call the admin API without an interactive login. Send them as
        the <code>X-API-Token</code> header (or a <code>Bearer</code> value starting with{" "}
        <code>opnl_</code>). The plaintext token is shown exactly once when created.
      </Typography>

      <Paper sx={{ height: 480 }}>
        <DataGrid
          rows={tokens ?? []}
          columns={columns}
          loading={isLoading}
          pagination
          pageSizeOptions={[10, 25, 50]}
          initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
          disableRowSelectionOnClick
        />
      </Paper>

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>New API token</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Label"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="e.g. ci-deploy"
              required
              fullWidth
            />
            <TextField
              select
              label="Role"
              value={role}
              onChange={(e) => setRole(e.target.value as "ADMIN" | "RESELLER")}
              fullWidth
              helperText="ADMIN for full panel access; RESELLER for scoped automation."
            >
              <MenuItem value="ADMIN">ADMIN</MenuItem>
              <MenuItem value="RESELLER">RESELLER</MenuItem>
            </TextField>
            <TextField
              label="Expiry (optional)"
              type="datetime-local"
              value={expiresAt}
              onChange={(e) => setExpiresAt(e.target.value)}
              InputLabelProps={{ shrink: true }}
              fullWidth
              helperText="Leave empty for a token that never expires."
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!label.trim() || create.isPending}
            onClick={() => create.mutate()}
          >
            {create.isPending ? "Creating…" : "Create"}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={!!created} maxWidth="sm" fullWidth>
        <DialogTitle>Token created</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 2 }}>
            Copy this token now — it will not be shown again. Anyone holding it can call the API
            with the selected role until it expires or is revoked.
          </DialogContentText>
          <Paper variant="outlined" sx={{ p: 2, bgcolor: "action.hover" }}>
            <Typography component="code" sx={{ wordBreak: "break-all", fontSize: 14 }}>
              {created?.rawToken}
            </Typography>
          </Paper>
        </DialogContent>
        <DialogActions>
          <Button onClick={copyToken}>Copy</Button>
          <Button variant="contained" onClick={closeCreated}>
            Done
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger
        confirmLabel="Revoke"
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
