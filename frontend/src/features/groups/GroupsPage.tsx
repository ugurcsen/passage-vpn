import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Box, Button, IconButton, Paper, Stack, Tooltip, Typography } from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import AddIcon from "@mui/icons-material/Add";
import EditIcon from "@mui/icons-material/Edit";
import DeleteIcon from "@mui/icons-material/Delete";
import GroupIcon from "@mui/icons-material/Group";
import TuneIcon from "@mui/icons-material/Tune";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { useAuth } from "@/hooks/useAuth";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { GroupFormDialog } from "./GroupFormDialog";
import { GroupMembersDialog } from "./GroupMembersDialog";
import { GroupPoolDialog } from "./GroupPoolDialog";

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
  const { user: currentUser } = useAuth();
  const isAdmin = currentUser?.role === "ADMIN";
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<GroupRow | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [parentId, setParentId] = useState("");
  const [tunnelMode, setTunnelMode] = useState("" as "" | "full" | "split");
  const [membersFor, setMembersFor] = useState<GroupRow | null>(null);
  const [selectedUsers, setSelectedUsers] = useState<string[]>([]);
  const [poolFor, setPoolFor] = useState<GroupRow | null>(null);
  const [poolInput, setPoolInput] = useState("");
  const [poolIpv6For, setPoolIpv6For] = useState<GroupRow | null>(null);
  const [poolIpv6Input, setPoolIpv6Input] = useState("");
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
        await api(endpoints.groups + `/${editing.id}`, { method: "PUT", body: JSON.stringify(payload) });
      } else {
        await api(endpoints.groups, { method: "POST", body: JSON.stringify(payload) });
      }
      if (editing) {
        const tunnelUrl = endpoints.groups + `/${editing.id}/settings/tunnel_mode`;
        if (tunnelMode) {
          await api(tunnelUrl, { method: "PUT", body: JSON.stringify(tunnelMode) });
        } else {
          await api(tunnelUrl, { method: "DELETE" });
        }
      }
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
    setTunnelMode("");
    setDialogOpen(true);
  };

  const openEdit = async (row: GroupRow) => {
    setEditing(row);
    setName(row.name);
    setDescription(row.description ?? "");
    setParentId(row.parentId ?? "");
    setTunnelMode("");
    try {
      const settings = await api<Record<string, unknown>>(endpoints.groups + `/${row.id}/settings`);
      const mode = settings.tunnel_mode;
      setTunnelMode(mode === "full" || mode === "split" ? mode : "");
    } catch {
      /* settings are optional; keep the dialog usable */
    }
    setDialogOpen(true);
  };

  const openMembers = async (row: GroupRow) => {
    setMembersFor(row);
    const memberIds = await api<string[]>(endpoints.groups + `/${row.id}/members`);
    setSelectedUsers(memberIds);
  };

  const poolMutation = useMutation({
    mutationFn: () =>
      api(endpoints.groups + `/${poolFor?.id}/static-ip-pool`, {
        method: "PUT",
        body: JSON.stringify({ pool: poolInput.trim() }),
      }),
    onSuccess: () => {
      toast.success("Static IP pool updated");
      setPoolFor(null);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const openPool = async (row: GroupRow) => {
    setPoolFor(row);
    try {
      const pool = await api<string | null>(endpoints.groups + `/${row.id}/static-ip-pool`);
      setPoolInput(pool ?? "");
    } catch {
      setPoolInput("");
    }
  };

  const poolIpv6Mutation = useMutation({
    mutationFn: () =>
      api(endpoints.groups + `/${poolIpv6For?.id}/static-ipv6-pool`, {
        method: "PUT",
        body: JSON.stringify({ pool: poolIpv6Input.trim() }),
      }),
    onSuccess: () => {
      toast.success("Static IPv6 pool updated");
      setPoolIpv6For(null);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const openPoolIpv6 = async (row: GroupRow) => {
    setPoolIpv6For(row);
    try {
      const pool = await api<string | null>(endpoints.groups + `/${row.id}/static-ipv6-pool`);
      setPoolIpv6Input(pool ?? "");
    } catch {
      setPoolIpv6Input("");
    }
  };

  const columns: GridColDef[] = [
    { field: "name", headerName: "Name", flex: 1, minWidth: 140 },
    { field: "description", headerName: "Description", flex: 1.4 },
    { field: "memberCount", headerName: "Members", width: 110 },
    {
      field: "parentId",
      headerName: "Parent",
      width: 140,
      renderCell: (params) => {
        const parent = groups?.find((g) => g.id === (params.row as GroupRow).parentId);
        return <>{parent?.name ?? ""}</>;
      },
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 190,
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
            <Tooltip title="Static IP pool">
              <IconButton size="small" onClick={() => openPool(row)} data-testid={`edit-pool-${row.name}`}>
                <TuneIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="Static IPv6 pool">
              <IconButton size="small" onClick={() => openPoolIpv6(row)} data-testid={`edit-pool6-${row.name}`}>
                <TuneIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="Edit">
              <IconButton size="small" onClick={() => openEdit(row)}>
                <EditIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            {(row.parentId || isAdmin) && (
              <Tooltip title="Delete">
                <IconButton size="small" onClick={() => setConfirm(row)}>
                  <DeleteIcon fontSize="small" color="error" />
                </IconButton>
              </Tooltip>
            )}
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

      <GroupFormDialog
        open={dialogOpen}
        editing={editing}
        name={name}
        description={description}
        parentId={parentId}
        tunnelMode={tunnelMode}
        groups={groups ?? []}
        isAdmin={isAdmin}
        onChangeName={setName}
        onChangeDescription={setDescription}
        onChangeParentId={setParentId}
        onChangeTunnelMode={setTunnelMode}
        onClose={() => setDialogOpen(false)}
        onSave={() => saveMutation.mutate()}
      />

      <GroupMembersDialog
        open={!!membersFor}
        groupName={membersFor?.name}
        users={users ?? []}
        selectedUserIds={selectedUsers}
        saving={membersMutation.isPending}
        onToggleUser={(userId, checked) =>
          setSelectedUsers((prev) => (checked ? [...prev, userId] : prev.filter((id) => id !== userId)))
        }
        onClose={() => setMembersFor(null)}
        onSave={() => membersMutation.mutate()}
      />

      <GroupPoolDialog
        open={!!poolFor}
        groupName={poolFor?.name}
        title="Static IP pool"
        fieldLabel="IP range"
        value={poolInput}
        placeholder="e.g. 10.8.0.100-10.8.0.199"
        helperText="Single IP range (e.g. 10.8.0.100-10.8.0.199). Empty clears the pool. Members can be auto-allocated from here."
        saving={poolMutation.isPending}
        onChange={setPoolInput}
        onClose={() => setPoolFor(null)}
        onSave={() => poolMutation.mutate()}
      />

      <GroupPoolDialog
        open={!!poolIpv6For}
        groupName={poolIpv6For?.name}
        title="Static IPv6 pool"
        fieldLabel="IPv6 range"
        value={poolIpv6Input}
        placeholder="e.g. fd00:1::100-fd00:1::1ff"
        helperText="Single IPv6 range (e.g. fd00:1::100-fd00:1::1ff). Empty clears the pool. Members can be auto-allocated from here."
        saving={poolIpv6Mutation.isPending}
        onChange={setPoolIpv6Input}
        onClose={() => setPoolIpv6For(null)}
        onSave={() => poolIpv6Mutation.mutate()}
      />

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
