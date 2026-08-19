import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  TextField,
} from "@mui/material";

export interface ResetPasswordDialogProps {
  target: { username: string } | null;
  password: string;
  onPasswordChange: (value: string) => void;
  onClose: () => void;
  onReset: () => void;
}

export function ResetPasswordDialog({
  target,
  password,
  onPasswordChange,
  onClose,
  onReset,
}: ResetPasswordDialogProps) {
  return (
    <Dialog open={!!target} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Reset password</DialogTitle>
      <DialogContent>
        <TextField
          label={`New password for ${target?.username ?? ""}`}
          type="password"
          value={password}
          onChange={(e) => onPasswordChange(e.target.value)}
          fullWidth
          sx={{ mt: 1 }}
          autoFocus
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={password.length < 8} onClick={onReset}>
          Reset
        </Button>
      </DialogActions>
    </Dialog>
  );
}
