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
import type { UserForm, GroupRow } from "./types";
import type { Role } from "@/hooks/useAuth";

export interface UserFormDialogProps {
  open: boolean;
  editing: string | null;
  form: UserForm;
  isAdmin: boolean;
  groups: GroupRow[];
  onChange: (patch: Partial<UserForm>) => void;
  onClose: () => void;
  onSave: () => void;
}

export function UserFormDialog({
  open,
  editing,
  form,
  isAdmin,
  groups,
  onChange,
  onClose,
  onSave,
}: UserFormDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{editing ? `Edit ${form.username}` : "New user"}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Username"
            value={form.username}
            onChange={(e) => onChange({ username: e.target.value })}
            disabled={!!editing}
            required
          />
          <TextField
            label={editing ? "New password (blank keeps current)" : "Password"}
            type="password"
            value={form.password}
            onChange={(e) => onChange({ password: e.target.value })}
            required={!editing}
            helperText={editing ? "At least 8 characters when changing." : "At least 8 characters."}
          />
          <TextField
            label="Full name"
            value={form.fullName}
            onChange={(e) => onChange({ fullName: e.target.value })}
          />
          <TextField
            label="Email"
            type="email"
            value={form.email}
            onChange={(e) => onChange({ email: e.target.value })}
          />
          {isAdmin && (
            <TextField
              select
              label="Role"
              value={form.role}
              onChange={(e) => onChange({ role: e.target.value as Role })}
            >
              <MenuItem value="USER">User</MenuItem>
              <MenuItem value="GROUP_ADMIN">Group admin</MenuItem>
              <MenuItem value="ADMIN">Admin</MenuItem>
            </TextField>
          )}
          {isAdmin && form.role === "GROUP_ADMIN" && (
            <TextField
              select
              label="Managed groups"
              value={form.adminGroupIds}
              onChange={(e) => onChange({ adminGroupIds: e.target.value as unknown as string[] })}
              SelectProps={{ multiple: true }}
              required
              helperText="Root groups this account manages, including all subgroups."
            >
              {groups.map((g) => (
                <MenuItem key={g.id} value={g.id}>
                  {g.name}
                </MenuItem>
              ))}
            </TextField>
          )}
          <TextField
            select
            label="Groups"
            value={form.groupIds}
            onChange={(e) => onChange({ groupIds: e.target.value as unknown as string[] })}
            SelectProps={{ multiple: true }}
            helperText="Groups inherit settings resolved per user."
          >
            {groups.map((g) => (
              <MenuItem key={g.id} value={g.id}>
                {g.name}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!form.username || (!form.password && !editing)}
          onClick={onSave}
        >
          {editing ? "Save" : "Create"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
