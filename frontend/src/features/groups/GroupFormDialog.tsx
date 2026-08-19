import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from "@mui/material";

export interface GroupFormDialogProps {
  open: boolean;
  editing: { id: string; name: string } | null;
  name: string;
  description: string;
  parentId: string;
  tunnelMode: "" | "full" | "split";
  groups: { id: string; name: string }[];
  isAdmin: boolean;
  onChangeName: (v: string) => void;
  onChangeDescription: (v: string) => void;
  onChangeParentId: (v: string) => void;
  onChangeTunnelMode: (v: "" | "full" | "split") => void;
  onClose: () => void;
  onSave: () => void;
}

export function GroupFormDialog({
  open,
  editing,
  name,
  description,
  parentId,
  tunnelMode,
  groups,
  isAdmin,
  onChangeName,
  onChangeDescription,
  onChangeParentId,
  onChangeTunnelMode,
  onClose,
  onSave,
}: GroupFormDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{editing ? `Edit ${editing.name}` : "New group"}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Name" value={name} onChange={(e) => onChangeName(e.target.value)} required />
          <TextField
            label="Description"
            value={description}
            onChange={(e) => onChangeDescription(e.target.value)}
            multiline
            minRows={2}
          />
          <TextField
            select
            label="Parent group"
            value={parentId}
            onChange={(e) => onChangeParentId(e.target.value)}
            helperText={
              isAdmin
                ? "Child groups inherit settings, overridden by the more specific group."
                : "Group admins can only create subgroups under a managed root group."
            }
          >
            <MenuItem value="" disabled={!isAdmin}>
              None
            </MenuItem>
            {groups
              .filter((g) => g.id !== editing?.id)
              .map((g) => (
                <MenuItem key={g.id} value={g.id}>
                  {g.name}
                </MenuItem>
              ))}
          </TextField>
          <TextField
            select
            label="Tunnel mode"
            value={tunnelMode}
            onChange={(e) => onChangeTunnelMode(e.target.value as "" | "full" | "split")}
            disabled={!editing}
            helperText={
              editing
                ? "Full routes all traffic through the VPN; split routes only the configured networks. Empty inherits the server default."
                : "Set tunnel mode after creating the group."
            }
          >
            <MenuItem value="">Inherit default</MenuItem>
            <MenuItem value="full">Full tunnel</MenuItem>
            <MenuItem value="split">Split tunnel</MenuItem>
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!name.trim() || (!isAdmin && !parentId)} onClick={onSave}>
          {editing ? "Save" : "Create"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
