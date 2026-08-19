import {
  Box,
  Button,
  IconButton,
  MenuItem,
  Paper,
  Skeleton,
  Stack,
  Switch,
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
import EditIcon from "@mui/icons-material/Edit";
import type { ServerSettings } from "@/lib/api";
import {
  displaySetting,
  KNOWN_SETTINGS,
  knownSetting,
} from "./knownSettings";

export interface ServerDefaultsTableProps {
  data: ServerSettings | undefined;
  isLoading: boolean;
  savePending: boolean;
  onOpenAdd: () => void;
  onOpenEdit: (key: string, value: unknown) => void;
  onToggleBoolean: (key: string, checked: boolean) => void;
  onToggleChoice: (key: string, value: string) => void;
  onDelete: (key: string, title: string, text: string) => void;
}

export function ServerDefaultsTable({
  data,
  isLoading,
  savePending,
  onOpenAdd,
  onOpenEdit,
  onToggleBoolean,
  onToggleChoice,
  onDelete,
}: ServerDefaultsTableProps) {
  const entries = Object.entries(data ?? {});
  const knownEntries = entries.filter(([k]) => knownSetting(k));
  const availableDefaults = KNOWN_SETTINGS.filter(
    (s) => s.type !== "serverConfig" && !knownEntries.some(([k]) => k === s.key),
  );

  return (
    <Paper sx={{ p: 3, mb: 3, overflowX: "auto" }}>
      <Stack
        direction={{ xs: "column", sm: "row" }}
        justifyContent="space-between"
        alignItems={{ xs: "flex-start", sm: "center" }}
        sx={{ mb: 2 }}
      >
        <Box>
          <Typography variant="h6" fontWeight={600}>
            Server defaults
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {`${knownEntries.length} configured`}
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          disabled={availableDefaults.length === 0}
          onClick={onOpenAdd}
        >
          Add default
        </Button>
      </Stack>

      {isLoading ? (
        <Skeleton height={120} />
      ) : knownEntries.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ py: 3, textAlign: "center" }}>
          No server defaults configured yet.
        </Typography>
      ) : (
        <Table size="small" sx={{ tableLayout: "fixed", width: "100%" }}>
          <TableHead>
            <TableRow>
              <TableCell sx={{ width: "35%" }}>Setting</TableCell>
              <TableCell sx={{ width: "45%" }}>Value</TableCell>
              <TableCell align="right" sx={{ width: "20%" }}>
                Actions
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {knownEntries.map(([k, v]) => {
              const setting = knownSetting(k)!;
              const isBoolean = setting.type === "boolean";
              return (
                <TableRow key={k}>
                  <TableCell>
                    <Typography variant="body2" fontWeight={600}>
                      {setting.label}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {setting.description}
                    </Typography>
                  </TableCell>
                  <TableCell sx={{ overflowWrap: "anywhere" }}>
                    {isBoolean ? (
                      <Stack direction="row" alignItems="center" spacing={1}>
                        <Switch
                          size="small"
                          checked={v === true || v === "true"}
                          onChange={(e) => onToggleBoolean(k, e.target.checked)}
                          disabled={savePending}
                          inputProps={{ "aria-label": setting.label }}
                        />
                        <Typography variant="body2" color="text.secondary">
                          {v === true || v === "true" ? "On" : "Off"}
                        </Typography>
                      </Stack>
                    ) : setting.type === "choice" ? (
                      <TextField
                        select
                        size="small"
                        value={v === null || v === undefined ? "" : String(v)}
                        onChange={(e) => onToggleChoice(k, e.target.value)}
                        disabled={savePending}
                        inputProps={{ "aria-label": setting.label }}
                        sx={{ minWidth: 140 }}
                      >
                        {(setting.options ?? []).map((opt) => (
                          <MenuItem key={opt} value={opt}>
                            {opt}
                          </MenuItem>
                        ))}
                      </TextField>
                    ) : (
                      <Typography variant="body2" color="text.secondary">
                        {displaySetting(setting.type, v)}
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell align="right">
                    <Stack direction="row" justifyContent="flex-end">
                      {!isBoolean && (
                        <Tooltip title="Edit value">
                          <IconButton size="small" aria-label={`Edit ${setting.label}`} onClick={() => onOpenEdit(k, v)}>
                            <EditIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      )}
                      <Tooltip title="Delete setting">
                        <IconButton
                          size="small"
                          aria-label={`Delete ${setting.label}`}
                          onClick={() =>
                            onDelete(
                              k,
                              "Delete setting",
                              `Delete the "${k}" setting? Accounts will fall back to group and per-user values.`,
                            )
                          }
                        >
                          <DeleteIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    </Stack>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </Paper>
  );
}
