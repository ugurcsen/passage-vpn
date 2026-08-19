import { Chip, IconButton, Stack, Tooltip, Typography } from "@mui/material";
import type { GridColDef } from "@mui/x-data-grid";
import DeleteIcon from "@mui/icons-material/Delete";
import BlockIcon from "@mui/icons-material/Block";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import KeyIcon from "@mui/icons-material/Key";
import LockResetIcon from "@mui/icons-material/LockReset";
import TuneIcon from "@mui/icons-material/Tune";
import VerifiedUserIcon from "@mui/icons-material/VerifiedUser";
import { formatDateTime, type UserRow } from "./types";

export interface UserColumnsProps {
  isAdmin: boolean;
  canManageRow: (row: UserRow) => boolean;
  onEdit: (row: UserRow) => void;
  onBan: (row: UserRow) => void;
  onResetPassword: (row: UserRow) => void;
  onCcdSettings: (row: UserRow) => void;
  onManageMfa: (row: UserRow) => void;
  onDelete: (ids: string[], usernames: string) => void;
}

export function getUserColumns({
  isAdmin,
  canManageRow,
  onEdit,
  onBan,
  onResetPassword,
  onCcdSettings,
  onManageMfa,
  onDelete,
}: UserColumnsProps): GridColDef[] {
  return [
    { field: "username", headerName: "Username", flex: 1.2, minWidth: 140 },
    { field: "fullName", headerName: "Full name", flex: 1, minWidth: 100 },
    {
      field: "groups",
      headerName: "Groups",
      width: 160,
      valueGetter: (_, row) => (row as UserRow).groups.join(", "),
      renderCell: (params) => (
        <Stack direction="row" spacing={0.5} sx={{ py: 0.5, flexWrap: "wrap" }}>
          {(params.value as string).split(", ").filter(Boolean).slice(0, 2).map((g) => (
            <Chip key={g} label={g} size="small" variant="outlined" />
          ))}
        </Stack>
      ),
    },
    {
      field: "adminGroupNames",
      headerName: "Manages",
      width: 160,
      valueGetter: (_, row) => (row as UserRow).adminGroupNames?.join(", ") ?? "",
      renderCell: (params) => (
        <Stack direction="row" spacing={0.5} sx={{ py: 0.5, flexWrap: "wrap" }}>
          {(params.value as string).split(", ").filter(Boolean).slice(0, 2).map((g) => (
            <Chip key={g} label={g} size="small" variant="outlined" color="warning" />
          ))}
        </Stack>
      ),
    },
    {
      field: "role",
      headerName: "Role",
      width: 100,
      renderCell: (params) => (
        <Chip
          label={params.value as string}
          size="small"
          color={params.value === "ADMIN" ? "secondary" : params.value === "GROUP_ADMIN" ? "warning" : "default"}
        />
      ),
    },
    {
      field: "mfaEnabled",
      headerName: "MFA",
      width: 100,
      renderCell: (params) => {
        const row = params.row as UserRow;
        if (row.mfaEnabled) return <Chip label="On" size="small" color="success" />;
        return row.mfaRequired ? (
          <Chip label="Required" size="small" color="warning" />
        ) : (
          <Chip label="Off" size="small" />
        );
      },
    },
    {
      field: "banned",
      headerName: "Status",
      width: 100,
      renderCell: (params) =>
        params.value ? (
          <Chip label="Disabled" size="small" color="error" />
        ) : (
          <Chip label="Active" size="small" color="success" />
        ),
    },
    {
      field: "lastLoginAt",
      headerName: "Last login",
      width: 150,
      valueGetter: (_, row) => (row as UserRow).lastLoginAt ?? "",
      renderCell: (params) => (
        <Typography variant="body2">{formatDateTime(params.value as string)}</Typography>
      ),
    },
    {
      field: "staticIp",
      headerName: "Static IP",
      width: 130,
      valueGetter: (_, row) => (row as UserRow).staticIp ?? "",
      renderCell: (params) =>
        params.value ? (
          <Chip label={params.value as string} size="small" variant="outlined" color="info" />
        ) : (
          <Typography variant="body2" color="text.secondary">\u2014</Typography>
        ),
    },
    {
      field: "staticIpv6",
      headerName: "Static IPv6",
      width: 190,
      valueGetter: (_, row) => (row as UserRow).staticIpv6 ?? "",
      renderCell: (params) =>
        params.value ? (
          <Chip label={params.value as string} size="small" variant="outlined" color="secondary" />
        ) : (
          <Typography variant="body2" color="text.secondary">\u2014</Typography>
        ),
    },
    {
      field: "actions",
      headerName: "Actions",
      width: 250,
      sortable: false,
      filterable: false,
      renderCell: (params) => {
        const row = params.row as UserRow;
        return (
          <Stack direction="row">
            {canManageRow(row) && (
              <Tooltip title="Edit">
                <IconButton size="small" onClick={() => onEdit(row)}>
                  <LockResetIcon fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
            {canManageRow(row) && (
              <Tooltip title={row.banned ? "Enable" : "Disable"}>
                <IconButton size="small" onClick={() => onBan(row)}>
                  {row.banned ? <CheckCircleIcon fontSize="small" color="success" /> : <BlockIcon fontSize="small" />}
                </IconButton>
              </Tooltip>
            )}
            {canManageRow(row) && (
              <Tooltip title="Reset password">
                <IconButton size="small" onClick={() => onResetPassword(row)}>
                  <KeyIcon fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
            {canManageRow(row) && (
              <Tooltip title="CCD settings">
                <IconButton size="small" onClick={() => onCcdSettings(row)} data-testid={`edit-ccd-${row.username}`}>
                  <TuneIcon fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
            {isAdmin && (
              <Tooltip title="Manage MFA">
                <IconButton size="small" onClick={() => onManageMfa(row)} data-testid={`manage-mfa-${row.username}`}>
                  <VerifiedUserIcon fontSize="small" color={row.mfaEnabled ? "success" : "action"} />
                </IconButton>
              </Tooltip>
            )}
            {canManageRow(row) && (
              <Tooltip title="Delete">
                <IconButton size="small" onClick={() => onDelete([row.id], row.username)}>
                  <DeleteIcon fontSize="small" color="error" />
                </IconButton>
              </Tooltip>
            )}
          </Stack>
        );
      },
    },
  ];
}
