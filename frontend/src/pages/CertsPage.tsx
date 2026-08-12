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
  IconButton,
  MenuItem,
  Paper,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import AddIcon from "@mui/icons-material/Add";
import BlockIcon from "@mui/icons-material/Block";
import CachedIcon from "@mui/icons-material/Cached";
import RestoreIcon from "@mui/icons-material/Restore";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

const EXPIRY_WARNING_MS = 30 * 24 * 60 * 60 * 1000;

interface CertificateRow {
  id: string;
  commonName: string;
  userId: string | null;
  username: string | null;
  status: "VALID" | "REVOKED" | "EXPIRED";
  serial?: string;
  issuedAt?: string;
  expiresAt?: string;
  revokedAt?: string;
}

interface UserRow {
  id: string;
  username: string;
}

const STATUS_COLOR: Record<CertificateRow["status"], "success" | "error" | "warning"> = {
  VALID: "success",
  REVOKED: "error",
  EXPIRED: "warning",
};

function isExpiringSoon(expiresAt: string | undefined): boolean {
  if (!expiresAt) return false;
  const left = new Date(expiresAt).getTime() - Date.now();
  return left > 0 && left <= EXPIRY_WARNING_MS;
}

export function CertsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [issueOpen, setIssueOpen] = useState(false);
  const [userId, setUserId] = useState("");
  const [confirm, setConfirm] = useState<{
    title: string;
    text: string;
    label: string;
    danger?: boolean;
    loading?: boolean;
    action: () => void;
  } | null>(null);

  const { data: certs, isLoading } = useQuery<CertificateRow[]>({
    queryKey: ["admin-certs"],
    queryFn: () => api<CertificateRow[]>(endpoints.certs),
  });

  const { data: users } = useQuery<UserRow[]>({
    queryKey: ["admin-users", ""],
    queryFn: () => api<UserRow[]>(endpoints.users),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-certs"] });

  const issue = useMutation({
    mutationFn: () => api(endpoints.certs, { method: "POST", body: JSON.stringify({ userId }) }),
    onSuccess: () => {
      toast.success("Certificate issued");
      setIssueOpen(false);
      setUserId("");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Issue failed"),
  });

  const revoke = useMutation({
    mutationFn: (id: string) => api(`${endpoints.certs}/${id}/revoke`, { method: "POST" }),
    onSuccess: () => {
      toast.success("Certificate revoked");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Revoke failed"),
  });

  const restore = useMutation({
    mutationFn: (id: string) => api(`${endpoints.certs}/${id}/restore`, { method: "POST" }),
    onSuccess: () => {
      toast.success("Certificate restored");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Restore failed"),
  });

  const rotate = useMutation({
    mutationFn: (id: string) => api(`${endpoints.certs}/${id}/rotate`, { method: "POST" }),
    onSuccess: () => {
      toast.success("Certificate rotated");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Rotate failed"),
  });

  const columns: GridColDef[] = [
    { field: "commonName", headerName: "Common name", flex: 1, minWidth: 140 },
    { field: "username", headerName: "User", flex: 1 },
    {
      field: "status",
      headerName: "Status",
      width: 90,
      renderCell: (params) => (
        <Chip label={params.value as string} size="small" color={STATUS_COLOR[params.value as CertificateRow["status"]]} />
      ),
    },
    { field: "serial", headerName: "Serial", width: 120 },
    {
      field: "issuedAt",
      headerName: "Issued",
      width: 150,
      valueGetter: (_, row) => (row as CertificateRow).issuedAt ? new Date((row as CertificateRow).issuedAt!).toLocaleString() : "—",
    },
    {
      field: "expiresAt",
      headerName: "Expires",
      width: 150,
      renderCell: (params) => {
        const row = params.row as CertificateRow;
        return (
          <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
            {row.expiresAt ? new Date(row.expiresAt).toLocaleString() : "—"}
            {isExpiringSoon(row.expiresAt) && (
              <Tooltip title={`Expires within ${Math.ceil((new Date(row.expiresAt!).getTime() - Date.now()) / 86_400_000)} days`}>
                <Chip label="Expiring" size="small" color="warning" />
              </Tooltip>
            )}
          </Box>
        );
      },
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 140,
      sortable: false,
      filterable: false,
      renderCell: (params) => {
        const row = params.row as CertificateRow;
        return (
          <Box sx={{ display: "inline-flex", alignItems: "center" }}>
            {row.status === "VALID" && (
              <>
                <Tooltip title="Rotate">
                  <IconButton
                    aria-label="Rotate certificate"
                    size="small"
                    onClick={() =>
                      setConfirm({
                        title: "Rotate certificate",
                        text: `Rotate ${row.commonName}'s certificate? A new one is issued and the current one is revoked.`,
                        label: "Rotate",
                        danger: false,
                        loading: rotate.isPending,
                        action: () => rotate.mutate(row.id),
                      })
                    }
                  >
                    <CachedIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
                <Tooltip title="Revoke">
                  <IconButton
                    aria-label="Revoke certificate"
                    size="small"
                    onClick={() =>
                      setConfirm({
                        title: "Revoke certificate",
                        text: `Revoke ${row.commonName}'s certificate? Clients using it will be rejected.`,
                        label: "Revoke",
                        danger: true,
                        loading: revoke.isPending,
                        action: () => revoke.mutate(row.id),
                      })
                    }
                  >
                    <BlockIcon fontSize="small" color="error" />
                  </IconButton>
                </Tooltip>
              </>
            )}
            {row.status === "REVOKED" && (
              <Tooltip title="Restore">
                <IconButton
                  aria-label="Restore certificate"
                  size="small"
                  onClick={() =>
                      setConfirm({
                        title: "Restore certificate",
                        text: `Re-verify ${row.commonName}'s revoked certificate? It will be accepted by the VPN server again.`,
                        label: "Restore",
                        danger: false,
                        loading: restore.isPending,
                        action: () => restore.mutate(row.id),
                      })
                  }
                >
                  <RestoreIcon fontSize="small" color="primary" />
                </IconButton>
              </Tooltip>
            )}
          </Box>
        );
      },
    },
  ];

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="h5" fontWeight={700}>
          Certificates
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setIssueOpen(true)}>
          Issue certificate
        </Button>
      </Box>
      <Paper sx={{ height: 620 }}>
        <DataGrid
          rows={certs ?? []}
          columns={columns}
          loading={isLoading}
          pagination
          pageSizeOptions={[10, 25, 50]}
          initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
        />
      </Paper>

      <Dialog open={issueOpen} onClose={() => setIssueOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Issue certificate</DialogTitle>
        <DialogContent>
          <TextField
            select
            label="User"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            fullWidth
            sx={{ mt: 1 }}
            autoFocus
            helperText="A client certificate is created for this user (skipped if a valid one exists)."
          >
            {(users ?? []).map((u) => (
              <MenuItem key={u.id} value={u.id}>
                {u.username}
              </MenuItem>
            ))}
          </TextField>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setIssueOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={!userId} onClick={() => issue.mutate()}>
            Issue
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger={confirm?.danger}
        confirmLabel={confirm?.label ?? "Confirm"}
        loading={confirm?.loading}
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          confirm?.action();
          setConfirm(null);
        }}
      />
    </Box>
  );
}
