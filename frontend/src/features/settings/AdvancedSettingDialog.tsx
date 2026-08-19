import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from "@mui/material";
import type { AdvancedDialog } from "./types";
import { ADVANCED_KEY_PATTERN } from "./types";

export interface AdvancedSettingDialogProps {
  open: boolean;
  dialog: AdvancedDialog;
  savePending: boolean;
  onChange: (patch: Partial<AdvancedDialog>) => void;
  onClose: () => void;
  onSave: () => void;
}

export function AdvancedSettingDialog({
  open,
  dialog,
  savePending,
  onChange,
  onClose,
  onSave,
}: AdvancedSettingDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{dialog.isNew ? "Add custom setting" : dialog.key ? `Edit ${dialog.key}` : "Add custom setting"}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Key"
            value={dialog.key}
            onChange={(e) => onChange({ key: e.target.value })}
            disabled={!dialog.isNew}
            helperText="Letters, numbers, dots, dashes and underscores (1-64 chars)"
            placeholder="e.g. support_email"
          />
          <TextField
            label="Value (JSON)"
            value={dialog.value}
            onChange={(e) => onChange({ value: e.target.value })}
            multiline
            minRows={4}
            placeholder='e.g. "admin@example.com" or {"limit": 5}'
            sx={{ fontFamily: "monospace" }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!ADVANCED_KEY_PATTERN.test(dialog.key.trim()) || savePending}
          onClick={onSave}
        >
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}
