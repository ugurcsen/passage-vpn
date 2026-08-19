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
  withDaemon,
  type Daemon,
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
  daemonIndex: number | null;
  source: "ADMIN" | "PORTAL";
  expiresAt?: string;
  usesLeft: number | null;
  createdAt: string;
  revoked: boolean;
}

type TokenState = "ACTIVE" | "USED_UP" | "EXPIRED" | "REVOKED";

const STATE_LABEL: Record<TokenState, string> = {
  ACTIVE: "Active",
  USED_UP: "Used up",
  EXPIRED: "Expired",
  REVOKED: "Revoked",
};

const STATE_COLOR: Record<TokenState, "success" | "warning" | "info" | "error"> = {
  ACTIVE: "success",
  USED_UP: "warning",
  EXPIRED: "info",
  REVOKED: "error",
};

/** Effective state of a token: revoked wins, then used up, then expired. */
function tokenState(t: TokenRow): TokenState {
  if (t.revoked) return "REVOKED";
  if (t.usesLeft != null && t.usesLeft <= 0) return "USED_UP";
  if (t.expiresAt && Date.parse(t.expiresAt) <= Date.now()) return "EXPIRED";
  return "ACTIVE";
}

const PROFILE_TYPES: ProfileType[] = ["USER_LOCKED", "AUTO_LOGIN", "SERVER_LOCKED", "GENERIC"];

const TYPE_LABEL: Record<ProfileType, string> = {
  USER_LOCKED: "User-locked",
  AUTO_LOGIN: "Auto-login",
  SERVER_LOCKED: "Server-locked",
  GENERIC: "Generic",
};

/** Whether a daemon's flag combination serves the given profile type (mirrors the backend). */
function servesProfile(d: Daemon, type: ProfileType): boolean {
  switch (type) {
    case "GENERIC":
      return d.enabled && d.clientCertNotRequired;
    case "AUTO_LOGIN":
      return d.enabled && !d.clientCertNotRequired && !d.authUserPass;
    case "USER_LOCKED":
    case "SERVER_LOCKED":
      return d.enabled && !d.clientCertNotRequired && d.authUserPass;
  }
}

/** Human label for a daemon option. */
function daemonLabel(d: Daemon): string {
  const name = d.name ?? `Daemon ${d.daemonIndex}`;
  const routing = d.fullTunnel ? "full tunnel" : "split tunnel";
  return `${name} · ${routing} (${d.proto.toUpperCase()}:${d.port})`;
}

export function ProfilesPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dlUser, setDlUser] = useState("");
  const [dlType, setDlType] = useState<ProfileType>("USER_LOCKED");
  const [dlDaemon, setDlDaemon] = useState<number | null>(null);
  const [tokenOpen, setTokenOpen] = useState(false);
  const [tokenType, setTokenType] = useState<ProfileType>("USER_LOCKED");
  const [tokenDaemon, setTokenDaemon] = useState<number | null>(null);
  const [tokenUser, setTokenUser] = useState("");
  const [tokenExpiresDays, setTokenExpiresDays] = useState("30");
  const [tokenUses, setTokenUses] = useState("");
  const [confirm, setConfirm] = useState<{ title: string; text: string; action: () => void } | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<TokenState | "ALL">("ALL");
  const [sourceFilter, setSourceFilter] = useState<"ALL" | "ADMIN" | "PORTAL">("ALL");

  const { data: users } = useQuery<UserRow[]>({
    queryKey: ["admin-users", ""],
    queryFn: () => api<UserRow[]>(endpoints.users),
  });

  const { data: daemons } = useQuery<Daemon[]>({
    queryKey: ["admin-daemons"],
    queryFn: () => api<Daemon[]>(endpoints.daemons),
  });

  const { data: tokens } = useQuery<TokenRow[]>({
    queryKey: ["admin-tokens"],
    queryFn: () => api<TokenRow[]>(endpoints.profileTokens),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-tokens"] });

  /** Daemons that serve the currently selected download profile type. */
  const serving = (daemons ?? []).filter((d) => servesProfile(d, dlType));

  const download = async () => {
    if (!dlUser) return;
    setDownloading(true);
    try {
      const file = await api<OvpnFile>(
        withDaemon(`${endpoints.users}/${dlUser}/profiles/${dlType}/download`, dlDaemon),
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
          daemonIndex: tokenDaemon,
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

  const daemonName = (index: number | null): string => {
    if (index == null) return "— (auto)";
    const d = (daemons ?? []).find((x) => x.daemonIndex === index);
    return d?.name ?? `Daemon ${index}`;
  };

  const filteredTokens = (tokens ?? []).filter((t) => {
    if (statusFilter !== "ALL" && tokenState(t) !== statusFilter) return false;
    if (sourceFilter !== "ALL" && t.source !== sourceFilter) return false;
    return true;
  });

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
            onChange={(e) => {
              setDlType(e.target.value as ProfileType);
              setDlDaemon(null);
            }}
            sx={{ width: 200 }}
          >
            {PROFILE_TYPES.map((t) => (
              <MenuItem key={t} value={t}>
                {TYPE_LABEL[t]}
              </MenuItem>
            ))}
          </TextField>
          {serving.length > 1 && (
            <TextField
              select
              label="Server"
              value={dlDaemon ?? ""}
              onChange={(e) => setDlDaemon(e.target.value === "" ? null : Number(e.target.value))}
              sx={{ width: 260 }}
            >
              <MenuItem value="">Auto (all)</MenuItem>
              {serving.map((d) => (
                <MenuItem key={d.id} value={d.daemonIndex}>
                  {daemonLabel(d)}
                </MenuItem>
              ))}
            </TextField>
          )}
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

      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          mb: 2,
          gap: 2,
          flexWrap: "wrap",
        }}
      >
        <Typography variant="subtitle1" fontWeight={600}>
          Share links
        </Typography>
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap">
          <TextField
            select
            size="small"
            label="Status"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as TokenState | "ALL")}
            sx={{ minWidth: 130 }}
          >
            <MenuItem value="ALL">All</MenuItem>
            <MenuItem value="ACTIVE">Active</MenuItem>
            <MenuItem value="USED_UP">Used up</MenuItem>
            <MenuItem value="EXPIRED">Expired</MenuItem>
            <MenuItem value="REVOKED">Revoked</MenuItem>
          </TextField>
          <TextField
            select
            size="small"
            label="Source"
            value={sourceFilter}
            onChange={(e) => setSourceFilter(e.target.value as "ALL" | "ADMIN" | "PORTAL")}
            sx={{ minWidth: 130 }}
          >
            <MenuItem value="ALL">All</MenuItem>
            <MenuItem value="ADMIN">Admin</MenuItem>
            <MenuItem value="PORTAL">Portal QR</MenuItem>
          </TextField>
          <Button variant="outlined" startIcon={<AddIcon />} onClick={() => setTokenOpen(true)}>
            New share link
          </Button>
        </Stack>
      </Box>
      <TableContainer component={Paper}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>User</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>Source</TableCell>
              <TableCell>Server</TableCell>
              <TableCell>Expires</TableCell>
              <TableCell>Uses left</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {filteredTokens.map((t) => {
              const state = tokenState(t);
              return (
                <TableRow key={t.id}>
                  <TableCell>{t.username ?? "— (generic)"}</TableCell>
                  <TableCell>{TYPE_LABEL[t.profileType]}</TableCell>
                  <TableCell>
                    {t.source === "PORTAL" ? (
                      <Chip label="Portal QR" size="small" color="primary" variant="outlined" />
                    ) : (
                      <Chip label="Admin" size="small" variant="outlined" />
                    )}
                  </TableCell>
                  <TableCell>{daemonName(t.daemonIndex)}</TableCell>
                  <TableCell>
                    {t.expiresAt ? new Date(t.expiresAt).toLocaleString() : "Never"}
                  </TableCell>
                  <TableCell
                    sx={{
                      color: t.usesLeft != null && t.usesLeft <= 0 ? "error.main" : "inherit",
                    }}
                  >
                    {t.usesLeft ?? "Unlimited"}
                  </TableCell>
                  <TableCell>
                    <Chip label={STATE_LABEL[state]} size="small" color={STATE_COLOR[state]} />
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip
                      title={state === "ACTIVE" ? "Copy link" : `Cannot copy: ${STATE_LABEL[state]}`}
                    >
                      <span>
                        <IconButton
                          size="small"
                          disabled={state !== "ACTIVE"}
                          onClick={() => copyLink(t.token)}
                        >
                          <CopyAllIcon fontSize="small" />
                        </IconButton>
                      </span>
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
              );
            })}
            {(tokens ?? []).length === 0 && (
              <TableRow>
                <TableCell colSpan={8} sx={{ textAlign: "center", color: "text.secondary" }}>
                  No share links yet.
                </TableCell>
              </TableRow>
            )}
            {(tokens ?? []).length > 0 && filteredTokens.length === 0 && (
              <TableRow>
                <TableCell colSpan={8} sx={{ textAlign: "center", color: "text.secondary" }}>
                  No links match the filter.
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
              onChange={(e) => {
                setTokenType(e.target.value as ProfileType);
                setTokenDaemon(null);
              }}
            >
              {PROFILE_TYPES.map((t) => (
                <MenuItem key={t} value={t}>
                  {TYPE_LABEL[t]}
                </MenuItem>
              ))}
            </TextField>
            {(daemons ?? []).filter((d) => servesProfile(d, tokenType)).length > 1 && (
              <TextField
                select
                label="Server"
                value={tokenDaemon ?? ""}
                onChange={(e) => setTokenDaemon(e.target.value === "" ? null : Number(e.target.value))}
              >
                <MenuItem value="">Auto (all)</MenuItem>
                {(daemons ?? [])
                  .filter((d) => servesProfile(d, tokenType))
                  .map((d) => (
                    <MenuItem key={d.id} value={d.daemonIndex}>
                      {daemonLabel(d)}
                    </MenuItem>
                  ))}
              </TextField>
            )}
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
