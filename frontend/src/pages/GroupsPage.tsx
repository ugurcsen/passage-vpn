import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import AddIcon from "@mui/icons-material/Add";
import EditIcon from "@mui/icons-material/Edit";
import DeleteIcon from "@mui/icons-material/Delete";
import GroupIcon from "@mui/icons-material/Group";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

interface GroupRow {
  id: string;
  name: string;
  description?: string;
  parentId?: string;
  memberCount: number;
  createdAt?: string;
}

interface UserRow {
  id: string;
  username: string;
}

export function GroupsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<GroupRow | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [parentId, setParentId] = useState("");
  const [membersFor, setMembersFor] = useState<GroupRow | null>(null);
  const [selectedUsers, setSelectedUsers] = useState<string[]>([]);
  const [confirm, setConfirm] = useState<GroupRow | null>(null);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-groups"] });

  const { data: groups, isLoading } = useQuery<GroupRow[]>({
    queryKey: ["admin-groups"],
    queryFn: () => api<GroupRow[]>(endpoints.groups),
  });

  const { data: users } = useQuery<UserRow[]>({
    queryKey: ["admin-users"],
    queryFn: () => api<UserRow[]>(endpoints.users),
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const payload = { name, description: description || null, parentId: parentId || null };
      if (editing) {
        return api(endpoints.groups + `/${editing.id}`, { method: "PUT", body: JSON.stringify(payload) });
      }
      return api(endpoints.groups, { method: "POST", body: JSON.stringify(payload) });
    },
    onSuccess: () => {
      toast.success(editing ? "Group updated" : "Group created");
      setDialogOpen(false);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api(endpoints.groups + `/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      toast.success("Group deleted");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const membersMutation = useMutation({
    mutationFn: () =>
      api(endpoints.groups + `/${membersFor?.id}/members`, {
        method: "PUT",
        body: JSON.stringify({ userIds: selectedUsers }),
      }),
    onSuccess: () => {
      toast.success("Members updated");
      setMembersFor(null);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const openCreate = () => {
    setEditing(null);
    setName("");
    setDescription("");
    setParentId("");
    setDialogOpen(true);
  };

  const openEdit = (row: GroupRow) => {
    setEditing(row);
    setName(row.name);
    setDescription(row.description ?? "");
    setParentId(row.parentId ?? "");
    setDialogOpen(true);
  };

  const openMembers = async (row: GroupRow) => {
    setMembersFor(row);
    const memberIds = await api<string[]>(endpoints.groups + `/${row.id}/members`);
    setSelectedUsers(memberIds);
  };

  const columns: GridColDef[] = [
    { field: "name", headerName: "Name", flex: 1, minWidth: 140 },
    { field: "description", headerName: "Description", flex: 1.4 },
    { field: "memberCount", headerName: "Members", width: 110 },
    { field: "parentId", headerName: "Parent", width: 140 },
    {
      field: "actions",
      headerName: "Actions",
      width: 150,
      sortable: false,
      filterable: false,
      renderCell: (params) => {
        const row = params.row as GroupRow;
        return (
          <Stack direction="row">
            <Tooltip title="Edit members">
              <IconButton size="small" onClick={() => openMembers(row)}>
                <GroupIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="Edit">
              <IconButton size="small" onClick={() => openEdit(row)}>
                <EditIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="Delete">
              <IconButton size="small" onClick={() => setConfirm(row)}>
                <DeleteIcon fontSize="small" color="error" />
              </IconButton>
            </Tooltip>
          </Stack>
        );
      },
    },
  ];

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="h5" fontWeight={700}>
          Groups
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
          New group
        </Button>
      </Box>
      <Paper sx={{ height: 620 }}>
        <DataGrid
          rows={groups ?? []}
          columns={columns}
          loading={isLoading}
          pagination
          pageSizeOptions={[10, 25, 50]}
          disableRowSelectionOnClick
        />
      </Paper>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? `Edit ${editing.name}` : "New group"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} required />
            <TextField
              label="Description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              multiline
              minRows={2}
            />
            <TextField
              select
              label="Parent group"
              value={parentId}
              onChange={(e) => setParentId(e.target.value)}
              helperText="Child groups inherit settings, overridden by the more specific group."
            >
              <MenuItem value="">None</MenuItem>
              {(groups ?? [])
                .filter((g) => g.id !== editing?.id)
                .map((g) => (
                  <MenuItem key={g.id} value={g.id}>
                    {g.name}
                  </MenuItem>
                ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={!name.trim()} onClick={() => saveMutation.mutate()}>
            {editing ? "Save" : "Create"}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={!!membersFor} onClose={() => setMembersFor(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Members of {membersFor?.name}</DialogTitle>
        <DialogContent sx={{ maxHeight: 400, overflow: "auto" }}>
          <Stack>
            {(users ?? []).map((u) => (
              <FormControlLabel
                key={u.id}
                control={
                  <Checkbox
                    checked={selectedUsers.includes(u.id)}
                    onChange={(e) =>
                      setSelectedUsers((prev) =>
                        e.target.checked ? [...prev, u.id] : prev.filter((id) => id !== u.id),
                      )
                    }
                  />
                }
                label={u.username}
              />
            ))}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setMembersFor(null)}>Cancel</Button>
          <Button variant="contained" onClick={() => membersMutation.mutate()}>
            Save members
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={!!confirm}
        title="Delete group"
        message={`Delete ${confirm?.name}? Members keep their accounts; group settings are removed.`}
        danger
        confirmLabel="Delete"
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          if (confirm) deleteMutation.mutate(confirm.id);
          setConfirm(null);
        }}
      />
    </Box>
  );
}
