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
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import { api, endpoints, type ServerSettings } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

/** Server settings editor: generic JSON key/value store. */
export function SettingsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);

  const { data, isLoading, error } = useQuery<ServerSettings>({
    queryKey: ["admin-settings"],
    queryFn: () => api<ServerSettings>(endpoints.settings),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-settings"] });

  const save = useMutation({
    mutationFn: async (k: string) => {
      let parsed: unknown = value;
      try {
        parsed = JSON.parse(value);
      } catch {
        // Keep the raw string when the value is not valid JSON.
      }
      return api<ServerSettings>(`${endpoints.settings}/${encodeURIComponent(k)}`, {
        method: "PUT",
        body: JSON.stringify({ value: parsed }),
      });
    },
    onSuccess: (updated) => {
      queryClient.setQueryData(["admin-settings"], updated);
      toast.success("Setting saved");
      setKey("");
      setValue("");
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const remove = useMutation({
    mutationFn: (k: string) =>
      api<void>(`${endpoints.settings}/${encodeURIComponent(k)}`, { method: "DELETE" }),
    onSuccess: () => {
      toast.success("Setting deleted");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const entries = Object.entries(data ?? {});

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 3 }}>
        Settings
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {(error as Error).message}
        </Alert>
      )}

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
          Add setting
        </Typography>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
          <TextField
            label="Key"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            placeholder="e.g. support_email"
            sx={{ minWidth: 220 }}
          />
          <TextField
            label="Value (JSON)"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder='e.g. "admin@example.com" or {"limit": 5}'
            sx={{ flexGrow: 1 }}
          />
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            disabled={!key.trim() || save.isPending}
            onClick={() => save.mutate(key.trim())}
          >
            Add
          </Button>
        </Stack>
      </Paper>

      <Paper sx={{ p: 3 }}>
        <Typography variant="h6" fontWeight={600} sx={{ mb: 2 }}>
          Server settings ({entries.length})
        </Typography>
        {isLoading ? (
          <Skeleton height={120} />
        ) : (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Key</TableCell>
                <TableCell>Value</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {entries.map(([k, v]) => (
                <TableRow key={k}>
                  <TableCell sx={{ fontWeight: 600 }}>{k}</TableCell>
                  <TableCell sx={{ maxWidth: 420, overflowWrap: "anywhere" }}>
                    <Typography variant="body2" component="pre" sx={{ m: 0, fontFamily: "monospace" }}>
                      {JSON.stringify(v)}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="Delete setting">
                      <IconButton
                        size="small"
                        aria-label={`Delete ${k}`}
                        onClick={() =>
                          setConfirm({
                            title: "Delete setting",
                            text: `Delete the "${k}" setting?`,
                            action: () => remove.mutate(k),
                          })
                        }
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {entries.length === 0 && (
                <TableRow>
                  <TableCell colSpan={3} align="center" sx={{ color: "text.secondary" }}>
                    No server settings stored yet.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        )}
      </Paper>

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger
        confirmLabel="Delete"
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          confirm?.action();
          setConfirm(null);
        }}
      />
    </Box>
  );
}
