import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from "@mui/material";

export interface GroupPoolDialogProps {
  open: boolean;
  groupName: string | undefined;
  title: string;
  fieldLabel: string;
  value: string;
  placeholder: string;
  helperText: string;
  saving: boolean;
  onChange: (v: string) => void;
  onClose: () => void;
  onSave: () => void;
}

export function GroupPoolDialog({
  open,
  groupName,
  title,
  fieldLabel,
  value,
  placeholder,
  helperText,
  saving,
  onChange,
  onClose,
  onSave,
}: GroupPoolDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        {title} — {groupName}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label={fieldLabel}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            placeholder={placeholder}
            helperText={helperText}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={saving} onClick={onSave}>
          Save pool
        </Button>
      </DialogActions>
    </Dialog>
  );
}
