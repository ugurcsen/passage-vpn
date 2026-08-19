import {
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControlLabel,
  FormGroup,
} from "@mui/material";
import type { DeleteOptions } from "./types";

export interface DeleteDialogProps {
  open: boolean;
  target: { ids: string[]; usernames: string } | null;
  options: DeleteOptions;
  isPending: boolean;
  onOptionsChange: (patch: Partial<DeleteOptions>) => void;
  onClose: () => void;
  onConfirm: () => void;
}

export function DeleteDialog({
  open,
  target,
  options,
  isPending,
  onOptionsChange,
  onClose,
  onConfirm,
}: DeleteDialogProps) {
  return (
    <Dialog
      open={open}
      onClose={isPending ? undefined : onClose}
      maxWidth="xs"
      fullWidth
    >
      <DialogTitle>Delete {target?.usernames}</DialogTitle>
      <DialogContent>
        <DialogContentText sx={{ mb: 1 }}>
          This cannot be undone. The user's certificate is revoked and removed. Optionally clean up
          related resources:
        </DialogContentText>
        <FormGroup>
          <FormControlLabel
            control={
              <Checkbox
                checked={options.deleteAccessRules}
                onChange={(e) => onOptionsChange({ deleteAccessRules: e.target.checked })}
              />
            }
            label="Delete access rules"
          />
          <FormControlLabel
            control={
              <Checkbox
                checked={options.clearCcd}
                onChange={(e) => onOptionsChange({ clearCcd: e.target.checked })}
              />
            }
            label="Clear static IP"
          />
        </FormGroup>
      </DialogContent>
      <DialogActions>
        <Button disabled={isPending} onClick={onClose}>
          Cancel
        </Button>
        <Button variant="contained" color="error" disabled={isPending} onClick={onConfirm}>
          Delete
        </Button>
      </DialogActions>
    </Dialog>
  );
}
