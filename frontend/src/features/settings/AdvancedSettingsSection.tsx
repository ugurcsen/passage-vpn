import {
  Box,
  Button,
  IconButton,
  Paper,
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
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import type { ServerSettings } from "@/lib/api";
import { knownSetting } from "./knownSettings";

export interface AdvancedSettingsSectionProps {
  data: ServerSettings | undefined;
  showAdvanced: boolean;
  onToggleShow: () => void;
  onOpenAdd: () => void;
  onOpenEdit: (key: string, value: unknown) => void;
  onDelete: (key: string, title: string, text: string) => void;
}

export function AdvancedSettingsSection({
  data,
  showAdvanced,
  onToggleShow,
  onOpenAdd,
  onOpenEdit,
  onDelete,
}: AdvancedSettingsSectionProps) {
  const entries = Object.entries(data ?? {});
  const customEntries = entries.filter(([k]) => !knownSetting(k));

  return (
    <Paper sx={{ p: 3, overflowX: "auto" }}>
      <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={600}>
            Advanced settings
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {`Custom keys stored as raw JSON (${customEntries.length})`}
          </Typography>
        </Box>
        <Stack direction="row" spacing={1}>
          <Button
            variant="outlined"
            startIcon={<AddIcon />}
            onClick={onOpenAdd}
          >
            Add custom setting
          </Button>
          <IconButton
            aria-label="Toggle advanced settings"
            onClick={onToggleShow}
            sx={{ transform: showAdvanced ? "rotate(180deg)" : "none", transition: "transform 0.2s" }}
          >
            <ExpandMoreIcon />
          </IconButton>
        </Stack>
      </Stack>

      {showAdvanced &&
        (customEntries.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ py: 3, textAlign: "center" }}>
            No custom settings stored yet.
          </Typography>
        ) : (
          <Table size="small" sx={{ tableLayout: "fixed", width: "100%" }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: "30%" }}>Key</TableCell>
                <TableCell sx={{ width: "60%" }}>Value (JSON)</TableCell>
                <TableCell align="right" sx={{ width: "10%" }}>
                  Actions
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {customEntries.map(([k, v]) => (
                <TableRow key={k}>
                  <TableCell sx={{ fontWeight: 600, overflowWrap: "anywhere" }}>{k}</TableCell>
                  <TableCell sx={{ overflowWrap: "anywhere" }}>
                    <Typography
                      variant="body2"
                      component="pre"
                      sx={{ m: 0, fontFamily: "monospace", whiteSpace: "pre-wrap", fontSize: "0.8rem" }}
                    >
                      {JSON.stringify(v, null, 2)}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Stack direction="row" justifyContent="flex-end">
                      <Tooltip title="Edit value">
                        <IconButton
                          size="small"
                          aria-label={`Edit ${k}`}
                          onClick={() => onOpenEdit(k, v)}
                        >
                          <EditIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                      <Tooltip title="Delete setting">
                        <IconButton
                          size="small"
                          aria-label={`Delete ${k}`}
                          onClick={() => onDelete(k, "Delete setting", `Delete the "${k}" setting?`)}
                        >
                          <DeleteIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ))}
    </Paper>
  );
}
