import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  IconButton,
  Paper,
  Skeleton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import DownloadIcon from "@mui/icons-material/Download";
import RestoreIcon from "@mui/icons-material/Restore";
import { api, downloadBackup, endpoints, type BackupInfo, type RestoreResult } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Backups page: create, download and restore full-server backup archives. */
export function BackupsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [confirm, setConfirm] = useState<{ name: string } | null>(null);

  const { data, isLoading, error } = useQuery<BackupInfo[]>({
    queryKey: ["backups"],
    queryFn: () => api<BackupInfo[]>(endpoints.backups),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["backups"] });

  const create = useMutation({
    mutationFn: () => api<BackupInfo>(endpoints.backups, { method: "POST" }),
    onSuccess: (backup) => {
      toast.success(`Backup created: ${backup.name}`);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Create failed"),
  });

  const download = useMutation({
    mutationFn: (name: string) => downloadBackup(name),
    onError: (err) => toast.error(err instanceof Error ? err.message : "Download failed"),
  });

  const restore = useMutation({
    mutationFn: (name: string) =>
      api<RestoreResult>(`${endpoints.backups}/${encodeURIComponent(name)}/restore`, { method: "POST" }),
    onSuccess: (result) => {
      if (result.restartRequired) {
        toast.error(
          `${result.message} The backend must be restarted for the restored database to take effect.`,
        );
      } else {
        toast.success(result.message);
      }
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Restore failed"),
  });

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 1 }}>
        Backups
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Create a point-in-time archive of the database, PKI, configs and CCD files. Restoring
        replaces current data — a restart is required when the database was restored.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {(error as Error).message}
        </Alert>
      )}

      <Paper sx={{ p: 3, overflowX: "auto" }}>
        <Stack
          direction={{ xs: "column", sm: "row" }}
          justifyContent="space-between"
          alignItems={{ xs: "flex-start", sm: "center" }}
          sx={{ mb: 2 }}
        >
          <Typography variant="h6" fontWeight={600}>
            Backup archives
          </Typography>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            disabled={create.isPending}
            onClick={() => create.mutate()}
          >
            Create backup
          </Button>
        </Stack>

        {isLoading ? (
          <Skeleton height={120} />
        ) : !data || data.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ py: 3, textAlign: "center" }}>
            No backups yet. Create one to get started.
          </Typography>
        ) : (
          <Table size="small" sx={{ tableLayout: "fixed", width: "100%" }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: "45%" }}>Name</TableCell>
                <TableCell sx={{ width: "20%" }}>Size</TableCell>
                <TableCell sx={{ width: "25%" }}>Created</TableCell>
                <TableCell align="right" sx={{ width: "10%" }}>
                  Actions
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.map((b) => (
                <TableRow key={b.name}>
                  <TableCell sx={{ overflowWrap: "anywhere" }}>{b.name}</TableCell>
                  <TableCell>{formatBytes(b.sizeBytes)}</TableCell>
                  <TableCell>{formatDate(b.createdAt)}</TableCell>
                  <TableCell align="right">
                    <Stack direction="row" justifyContent="flex-end">
                      <Tooltip title="Download archive">
                        <IconButton
                          size="small"
                          aria-label={`Download ${b.name}`}
                          onClick={() => download.mutate(b.name)}
                          disabled={download.isPending}
                        >
                          <DownloadIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                      <Tooltip title="Restore from backup">
                        <IconButton
                          size="small"
                          aria-label={`Restore ${b.name}`}
                          onClick={() => setConfirm({ name: b.name })}
                        >
                          <RestoreIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Paper>

      <ConfirmDialog
        open={!!confirm}
        title="Restore backup"
        message={`Restore "${confirm?.name}"? Current data will be replaced with the contents of this archive.`}
        confirmLabel="Restore"
        danger
        loading={restore.isPending}
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          if (confirm) restore.mutate(confirm.name);
          setConfirm(null);
        }}
      />
    </Box>
  );
}
