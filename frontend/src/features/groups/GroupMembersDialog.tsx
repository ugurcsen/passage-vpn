import {
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
} from "@mui/material";

export interface GroupMembersDialogProps {
  open: boolean;
  groupName: string | undefined;
  users: { id: string; username: string }[];
  selectedUserIds: string[];
  saving: boolean;
  onToggleUser: (userId: string, checked: boolean) => void;
  onClose: () => void;
  onSave: () => void;
}

export function GroupMembersDialog({
  open,
  groupName,
  users,
  selectedUserIds,
  saving,
  onToggleUser,
  onClose,
  onSave,
}: GroupMembersDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Members of {groupName}</DialogTitle>
      <DialogContent sx={{ maxHeight: 400, overflow: "auto" }}>
        <Stack>
          {users.map((u) => (
            <FormControlLabel
              key={u.id}
              control={
                <Checkbox
                  checked={selectedUserIds.includes(u.id)}
                  onChange={(e) => onToggleUser(u.id, e.target.checked)}
                />
              }
              label={u.username}
            />
          ))}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={saving} onClick={onSave}>
          Save members
        </Button>
      </DialogActions>
    </Dialog>
  );
}
