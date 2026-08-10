import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import CopyAllIcon from "@mui/icons-material/CopyAll";
import DownloadIcon from "@mui/icons-material/Download";
import BlockIcon from "@mui/icons-material/Block";
import {
  api,
  copyToClipboard,
  downloadOvpn,
  endpoints,
  type OvpnFile,
  type ProfileType,
} from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog } from "@/components/ConfirmDialog";

interface UserRow {
  id: string;
  username: string;
}

interface TokenRow {
  id: string;
  token: string;
  userId: string | null;
  username: string | null;
  profileType: ProfileType;
  expiresAt?: string;
  usesLeft: number | null;
  createdAt: string;
  revoked: boolean;
}

const PROFILE_TYPES: ProfileType[] = ["USER_LOCKED", "AUTO_LOGIN", "SERVER_LOCKED", "GENERIC"];

const TYPE_LABEL: Record<ProfileType, string> = {
  USER_LOCKED: "User-locked",
  AUTO_LOGIN: "Auto-login",
  SERVER_LOCKED: "Server-locked",
  GENERIC: "Generic",
};

export function ProfilesPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dlUser, setDlUser] = useState("");
  const [dlType, setDlType] = useState<ProfileType>("USER_LOCKED");
  const [tokenOpen, setTokenOpen] = useState(false);
  const [tokenType, setTokenType] = useState<ProfileType>("USER_LOCKED");
  const [tokenUser, setTokenUser] = useState("");
  const [tokenExpiresDays, setTokenExpiresDays] = useState("30");
  const [tokenUses, setTokenUses] = useState("");
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);
  const [downloading, setDownloading] = useState(false);

  const { data: users } = useQuery<UserRow[]>({
    queryKey: ["admin-users", ""],
    queryFn: () => api<UserRow[]>(endpoints.users),
  });

  const { data: tokens } = useQuery<TokenRow[]>({
    queryKey: ["admin-tokens"],
    queryFn: () => api<TokenRow[]>(endpoints.profileTokens),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-tokens"] });

  const download = async () => {
    if (!dlUser) return;
    setDownloading(true);
    try {
      const file = await api<OvpnFile>(
        `${endpoints.users}/${dlUser}/profiles/${dlType}/download`,
      );
      downloadOvpn(file);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Download failed");
    } finally {
      setDownloading(false);
    }
  };

  const createToken = useMutation({
    mutationFn: () =>
      api(endpoints.profileTokens, {
        method: "POST",
        body: JSON.stringify({
          userId: tokenType === "GENERIC" ? null : tokenUser,
          profileType: tokenType,
          expiresAt: tokenExpiresDays
            ? new Date(Date.now() + Number(tokenExpiresDays) * 86400000).toISOString()
            : null,
          usesLeft: tokenUses ? Number(tokenUses) : null,
        }),
      }),
    onSuccess: () => {
      toast.success("Share link created");
      setTokenOpen(false);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Create failed"),
  });

  const revoke = useMutation({
    mutationFn: (id: string) => api(`${endpoints.profileTokens}/${id}/revoke`, { method: "POST" }),
    onSuccess: () => {
      toast.success("Share link revoked");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Revoke failed"),
  });

  const copyLink = async (token: string) => {
    const ok = await copyToClipboard(`${window.location.origin}/share/${token}`);
    if (ok) toast.success("Link copied");
    else toast.error("Copy failed");
  };

  const shareUrl = (token: string) => `${window.location.origin}/share/${token}`;

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 3 }}>
        Connection profiles
      </Typography>

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="subtitle1" fontWeight={600} sx={{ mb: 2 }}>
          Download a user&apos;s profile
        </Typography>
        <Stack direction="row" spacing={2} alignItems="flex-start">
          <TextField
            select
            label="User"
            value={dlUser}
            onChange={(e) => setDlUser(e.target.value)}
            sx={{ width: 240 }}
          >
            {(users ?? []).map((u) => (
              <MenuItem key={u.id} value={u.id}>
                {u.username}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Profile type"
            value={dlType}
            onChange={(e) => setDlType(e.target.value as ProfileType)}
            sx={{ width: 200 }}
          >
            {PROFILE_TYPES.map((t) => (
              <MenuItem key={t} value={t}>
                {TYPE_LABEL[t]}
              </MenuItem>
            ))}
          </TextField>
          <Button
            variant="contained"
            startIcon={<DownloadIcon />}
            disabled={!dlUser || downloading}
            onClick={download}
          >
            Download
          </Button>
        </Stack>
      </Paper>

      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="subtitle1" fontWeight={600}>
          Share links
        </Typography>
        <Button variant="outlined" startIcon={<AddIcon />} onClick={() => setTokenOpen(true)}>
          New share link
        </Button>
      </Box>
      <TableContainer component={Paper}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>User</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>Expires</TableCell>
              <TableCell>Uses left</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {(tokens ?? []).map((t) => (
              <TableRow key={t.id}>
                <TableCell>{t.username ?? "— (generic)"}</TableCell>
                <TableCell>{TYPE_LABEL[t.profileType]}</TableCell>
                <TableCell>
                  {t.expiresAt ? new Date(t.expiresAt).toLocaleDateString() : "Never"}
                </TableCell>
                <TableCell>{t.usesLeft ?? "Unlimited"}</TableCell>
                <TableCell>
                  {t.revoked ? <Chip label="Revoked" size="small" color="error" /> : <Chip label="Active" size="small" color="success" />}
                </TableCell>
                <TableCell align="right">
                  <Tooltip title="Copy link">
                    <IconButton size="small" disabled={t.revoked} onClick={() => copyLink(t.token)}>
                      <CopyAllIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                  <Tooltip title={t.revoked ? "Revoked" : "Revoke"}>
                    <span>
                      <IconButton
                        size="small"
                        disabled={t.revoked}
                        onClick={() =>
                          setConfirm({
                            title: "Revoke share link",
                            text: `Revoke the ${TYPE_LABEL[t.profileType]} share link?`,
                            action: () => revoke.mutate(t.id),
                          })
                        }
                      >
                        <BlockIcon fontSize="small" color={t.revoked ? "disabled" : "error"} />
                      </IconButton>
                    </span>
                  </Tooltip>
                </TableCell>
              </TableRow>
            ))}
            {(tokens ?? []).length === 0 && (
              <TableRow>
                <TableCell colSpan={6} sx={{ textAlign: "center", color: "text.secondary" }}>
                  No share links yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      <Dialog open={tokenOpen} onClose={() => setTokenOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>New share link</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              select
              label="Profile type"
              value={tokenType}
              onChange={(e) => setTokenType(e.target.value as ProfileType)}
            >
              {PROFILE_TYPES.map((t) => (
                <MenuItem key={t} value={t}>
                  {TYPE_LABEL[t]}
                </MenuItem>
              ))}
            </TextField>
            {tokenType !== "GENERIC" && (
              <TextField
                select
                label="User"
                value={tokenUser}
                onChange={(e) => setTokenUser(e.target.value)}
                required
              >
                {(users ?? []).map((u) => (
                  <MenuItem key={u.id} value={u.id}>
                    {u.username}
                  </MenuItem>
                ))}
              </TextField>
            )}
            <TextField
              label="Expires in (days, empty = never)"
              value={tokenExpiresDays}
              onChange={(e) => setTokenExpiresDays(e.target.value)}
            />
            <TextField
              label="Max uses (empty = unlimited)"
              value={tokenUses}
              onChange={(e) => setTokenUses(e.target.value)}
            />
            <Typography variant="caption" color="text.secondary">
              The link is opened in a browser and downloads the profile directly.{" "}
              {tokenType === "GENERIC"
                ? "Generic profiles are not tied to a specific user."
                : `Example: ${shareUrl("…")}`}
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setTokenOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={tokenType !== "GENERIC" && !tokenUser}
            onClick={() => createToken.mutate()}
          >
            Create
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title ?? ""}
        message={confirm?.text}
        danger
        confirmLabel="Revoke"
        loading={revoke.isPending}
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          confirm?.action();
          setConfirm(null);
        }}
      />
    </Box>
  );
}
